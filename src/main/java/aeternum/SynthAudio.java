package aeternum;

import java.util.Iterator;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.SourceDataLine;

/**
 * Fully procedural audio (port of {@code web/src/audio.js}): every sound is
 * synthesized in software, no files, no jME audio. A daemon mixer thread sums
 * the active voices into a 44100 Hz / 16-bit / stereo
 * {@link javax.sound.sampled.SourceDataLine}, soft-clipping the mix.
 *
 * <p>Voices come in two flavours, mirroring the WebAudio recipes:
 * <ul>
 *   <li><b>tones</b> — sine/saw/square/triangle oscillators with exponential
 *       frequency slides and an exponential attack + decay envelope;</li>
 *   <li><b>noise</b> — white noise through a sweepable band-pass / low-pass
 *       biquad filter with the same envelope shape.</li>
 * </ul>
 *
 * <p>If the platform refuses to open an audio line, every public method
 * becomes a graceful no-op.
 */
public class SynthAudio {

    private static final int SR = 44100;
    private static final double DT = 1.0 / SR;
    private static final int BLOCK = 512;             // frames per mix block (~11.6 ms)
    private static final double MASTER = 0.5;         // master gain, as in the JS port
    private static final double MIN_ENV = 0.0001;     // WebAudio exponential-ramp floor
    private static final int MAX_VOICES = 64;

    private static final int SINE = 0, SAW = 1, SQUARE = 2, TRIANGLE = 3;
    private static final AtomicLong SEED = new AtomicLong(System.nanoTime() | 1L);

    private final ConcurrentLinkedQueue<Voice> voices = new ConcurrentLinkedQueue<>();
    private SourceDataLine line;
    private Thread mixer;
    private volatile boolean enabled;
    private volatile boolean running;

    /** Mixer-clock seconds; written only by the mixer thread. */
    private double clock;

    private volatile boolean ambientOn;
    private volatile boolean bossActive;
    private volatile boolean bossReset;
    private double bossNextBar;   // mixer thread only
    private boolean bossFirstBar; // mixer thread only

    /** Opens the output line and starts the daemon mixer thread. */
    public SynthAudio() {
        try {
            AudioFormat fmt = new AudioFormat(SR, 16, 2, true, false);
            line = AudioSystem.getSourceDataLine(fmt);
            line.open(fmt, BLOCK * 4 * 4); // 4 blocks of device buffer (~46 ms)
            line.start();
            running = true;
            enabled = true;
            mixer = new Thread(this::mixLoop, "SynthAudio-Mixer");
            mixer.setDaemon(true);
            mixer.setPriority(Thread.MAX_PRIORITY - 1);
            mixer.start();
        } catch (Exception | LinkageError e) {
            if (line != null) {
                try { line.close(); } catch (Exception ignore) { }
            }
            line = null;
            enabled = false;
            running = false;
        }
    }

    // ------------------------------------------------------------------
    // combat
    // ------------------------------------------------------------------

    /** Light attack whoosh. */
    public void swing()      { noise(0.18, 2400, 2, 0.16, 500, false, 0); }

    /** Heavy attack whoosh, lower and longer. */
    public void swingHeavy() { noise(0.3, 1600, 2, 0.22, 280, false, 0); }

    /** Flesh impact. */
    public void hit() {
        noise(0.1, 3000, 1, 0.3, 900, false, 0);
        tone(140, 0.14, SQUARE, 0.18, 60, 0.01, 0);
    }

    /** Metal-on-metal ring (blocked/parried strikes). */
    public void clang() {
        tone(1900, 0.25, TRIANGLE, 0.12, 1500, 0.01, 0);
        noise(0.08, 4000, 4, 0.15, 0, false, 0);
    }

    /** Player takes damage. */
    public void hurt() {
        tone(220, 0.25, SAW, 0.2, 90, 0.01, 0);
        noise(0.15, 800, 1, 0.2, 200, false, 0);
    }

    /** Dodge roll tumble. */
    public void roll() { noise(0.25, 500, 0.8, 0.14, 150, true, 0); }

    /** Footstep thud. */
    public void step() { noise(0.05, 300, 0.5, 0.04, 0, true, 0); }

    /** Enemy death groan. */
    public void enemyDie() {
        tone(160, 0.6, SAW, 0.16, 40, 0.01, 0);
        noise(0.4, 600, 1, 0.18, 100, false, 0);
    }

    // ------------------------------------------------------------------
    // player feedback
    // ------------------------------------------------------------------

    /** Flask heal shimmer. */
    public void heal() {
        tone(520, 0.5, SINE, 0.12, 780, 0.01, 0);
        tone(660, 0.7, SINE, 0.1, 990, 0.2, 0);
    }

    /** Item pickup chime. */
    public void pickup() {
        tone(740, 0.18, SINE, 0.12, 0, 0.01, 0);
        tone(1110, 0.3, SINE, 0.1, 0, 0.06, 0);
    }

    /** Shrine rest chord (C-E-G-C arpeggio). */
    public void rest() {
        double[] fs = {262, 330, 392, 523};
        for (int i = 0; i < fs.length; i++) {
            tone(fs[i], 1.4, SINE, 0.09, 0, 0.1, i * 0.22);
        }
    }

    /** Level-up fanfare. */
    public void levelup() {
        double[] fs = {392, 494, 587, 784};
        for (int i = 0; i < fs.length; i++) {
            tone(fs[i], 0.5, SINE, 0.11, 0, 0.01, i * 0.11);
        }
    }

    /** MORTVVS ES — detuned descending drone. */
    public void death() {
        tone(110, 2.4, SAW, 0.2, 30, 0.3, 0);
        tone(116, 2.4, SAW, 0.15, 33, 0.3, 0);
    }

    /** Victory fanfare. */
    public void victory() {
        double[] fs = {523, 659, 784, 1047, 784, 1047};
        for (int i = 0; i < fs.length; i++) {
            tone(fs[i], 0.9, SINE, 0.12, 0, 0.05, i * 0.26);
        }
    }

    /** Boss awakening roar. */
    public void bossRoar() {
        tone(75, 1.4, SAW, 0.3, 45, 0.15, 0);
        noise(1.2, 350, 1.5, 0.25, 90, false, 0);
    }

    // ------------------------------------------------------------------
    // ambient + boss music
    // ------------------------------------------------------------------

    /** Starts the endless low wind (filtered noise with a slow LFO swell). Idempotent. */
    public void startAmbient() {
        if (!enabled || ambientOn) return;
        ambientOn = true;
        voices.add(new AmbientVoice());
    }

    /** Starts the war-drum ostinato + low saw drone, scheduled on the mixer clock. */
    public void startBossMusic() {
        if (!enabled || bossActive) return;
        bossReset = true;  // mixer thread picks this up and aligns the first bar
        bossActive = true;
    }

    /** Stops scheduling further boss-music bars (voices already playing ring out). */
    public void stopBossMusic() {
        bossActive = false;
    }

    /** Stops the mixer thread and closes the audio line. Idempotent. */
    public void shutdown() {
        enabled = false;
        bossActive = false;
        running = false;
        if (mixer != null) {
            try { mixer.join(500); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            mixer = null;
        }
        if (line != null) {
            try {
                line.stop();
                line.flush();
                line.close();
            } catch (Exception ignore) { }
            line = null;
        }
        voices.clear();
    }

    // ------------------------------------------------------------------
    // voice spawning
    // ------------------------------------------------------------------

    /**
     * Oscillator burst. Mirrors the JS {@code tone()}: exponential ramp from
     * {@code MIN_ENV} to {@code gain} over {@code attack}, then exponential
     * decay back to {@code MIN_ENV} at {@code dur}; frequency slides
     * exponentially from {@code freq} to {@code slideTo} (0 = no slide).
     */
    private void tone(double freq, double dur, int wave, double gain,
                      double slideTo, double attack, double delay) {
        add(new ToneVoice(freq, dur, wave, gain, slideTo, attack, delay));
    }

    /**
     * Filtered white-noise burst. Mirrors the JS {@code noise()}: fixed 10 ms
     * attack, exponential decay, filter cutoff slides exponentially from
     * {@code fc} to {@code slideTo} (0 = no slide).
     */
    private void noise(double dur, double fc, double q, double gain,
                       double slideTo, boolean lowpass, double delay) {
        add(new NoiseVoice(dur, fc, q, gain, slideTo, lowpass, delay));
    }

    private void add(Voice v) {
        if (!enabled || voices.size() >= MAX_VOICES) return;
        voices.add(v);
    }

    // ------------------------------------------------------------------
    // mixer thread
    // ------------------------------------------------------------------

    private void mixLoop() {
        byte[] buf = new byte[BLOCK * 4];
        double[] mix = new double[BLOCK];
        try {
            while (running) {
                scheduleBossMusic();
                java.util.Arrays.fill(mix, 0.0);
                for (Iterator<Voice> it = voices.iterator(); it.hasNext(); ) {
                    Voice v = it.next();
                    v.render(mix, BLOCK);
                    if (v.finished) it.remove();
                }
                for (int i = 0; i < BLOCK; i++) {
                    int s = (int) (Math.tanh(mix[i] * MASTER) * 32767.0);
                    buf[i * 4]     = (byte) s;
                    buf[i * 4 + 1] = (byte) (s >> 8);
                    buf[i * 4 + 2] = (byte) s;
                    buf[i * 4 + 3] = (byte) (s >> 8);
                }
                line.write(buf, 0, buf.length); // blocking write paces the loop
                clock += BLOCK * DT;
            }
        } catch (Exception e) {
            // line yanked away (device unplugged / closed) — go silent
            enabled = false;
        }
    }

    /**
     * JS reference: an immediate beat + 55 Hz drone, then every 1440 ms a
     * beat + 55 Hz and 65.4 Hz drones. The 360 ms echo beat is a delayed
     * voice. Bars are anchored to the mixer clock so they never drift.
     */
    private void scheduleBossMusic() {
        if (!bossActive) return;
        if (bossReset) {
            bossReset = false;
            bossNextBar = clock;
            bossFirstBar = true;
        }
        while (bossActive && bossNextBar <= clock) {
            double off = Math.max(0, bossNextBar - clock); // sample-block accuracy
            // war drum: deep sine thump + low noise skin slap
            tone(95, 0.4, SINE, 0.3, 38, 0.01, off);
            noise(0.12, 200, 1, 0.12, 0, true, off);
            // quieter echo beat 360 ms in
            tone(95, 0.3, SINE, 0.18, 38, 0.01, off + 0.36);
            // low saw drone chord
            tone(55, 3, SAW, 0.05, 0, 0.6, off);
            if (!bossFirstBar) tone(65.4, 3, SAW, 0.035, 0, 0.6, off);
            bossFirstBar = false;
            bossNextBar += 1.44;
        }
    }

    // ------------------------------------------------------------------
    // voices
    // ------------------------------------------------------------------

    /** A sample generator summed by the mixer; removed once {@code finished}. */
    private abstract static class Voice {
        int delaySamples;
        boolean finished;

        abstract void render(double[] out, int n);

        /** Consumes the pre-delay; returns the start index in this block, or -1 to skip it. */
        final int delayStart(int n) {
            if (delaySamples <= 0) return 0;
            if (delaySamples >= n) {
                delaySamples -= n;
                return -1;
            }
            int i = delaySamples;
            delaySamples = 0;
            return i;
        }

        static long seed() {
            return SEED.getAndAdd(0x9E3779B97F4A7C15L) | 1L;
        }
    }

    /** Shared exponential attack + decay envelope (WebAudio exponentialRamp shape). */
    private abstract static class EnvVoice extends Voice {
        final double dur, peak, attack;
        final double attackFactor, decayFactor;
        double env = MIN_ENV;
        double t;

        EnvVoice(double dur, double gain, double attack, double delay) {
            this.dur = dur;
            this.peak = gain;
            this.attack = Math.max(Math.min(attack, dur * 0.9), 1e-3);
            // exponential ramps become a constant per-sample multiplier
            this.attackFactor = Math.pow(gain / MIN_ENV, DT / this.attack);
            this.decayFactor = Math.pow(MIN_ENV / gain, DT / Math.max(dur - this.attack, 1e-3));
            this.delaySamples = (int) (delay * SR);
        }

        /** Advances time and returns the envelope value for the current sample. */
        final double stepEnv() {
            env = t < attack ? Math.min(env * attackFactor, peak) : env * decayFactor;
            t += DT;
            return env;
        }
    }

    /** Oscillator with exponential frequency slide. */
    private static final class ToneVoice extends EnvVoice {
        final int wave;
        final double freqFactor;
        double freq, phase;

        ToneVoice(double freq, double dur, int wave, double gain,
                  double slideTo, double attack, double delay) {
            super(dur, gain, attack, delay);
            this.wave = wave;
            this.freq = freq;
            double target = slideTo > 0 ? Math.max(slideTo, 1.0) : freq;
            this.freqFactor = Math.pow(target / freq, DT / dur);
        }

        @Override
        void render(double[] out, int n) {
            int i = delayStart(n);
            if (i < 0) return;
            for (; i < n; i++) {
                if (t >= dur) {
                    finished = true;
                    return;
                }
                double e = stepEnv();
                phase += freq * DT;
                if (phase >= 1.0) phase -= Math.floor(phase);
                out[i] += osc(wave, phase) * e;
                freq *= freqFactor;
            }
        }

        static double osc(int wave, double p) {
            switch (wave) {
                case SAW:      return 2.0 * p - 1.0;
                case SQUARE:   return p < 0.5 ? 1.0 : -1.0;
                case TRIANGLE: return p < 0.5 ? 4.0 * p - 1.0 : 3.0 - 4.0 * p;
                default:       return Math.sin(2.0 * Math.PI * p);
            }
        }
    }

    /** White noise through a sweepable band-pass / low-pass biquad. */
    private static final class NoiseVoice extends EnvVoice {
        final Biquad filter = new Biquad();
        final boolean lowpass, sliding;
        final double q, fcFactor;
        double fc;
        long rng = seed();

        NoiseVoice(double dur, double fc, double q, double gain,
                   double slideTo, boolean lowpass, double delay) {
            super(dur, gain, 0.01, delay);
            this.lowpass = lowpass;
            this.q = q;
            this.fc = fc;
            this.sliding = slideTo > 0;
            double target = sliding ? Math.max(slideTo, 1.0) : fc;
            this.fcFactor = Math.pow(target / fc, DT / dur);
            filter.set(fc, q, lowpass);
        }

        @Override
        void render(double[] out, int n) {
            int i = delayStart(n);
            if (i < 0) return;
            for (; i < n; i++) {
                if (t >= dur) {
                    finished = true;
                    return;
                }
                double e = stepEnv();
                if (sliding) {
                    fc *= fcFactor;
                    filter.set(fc, q, lowpass);
                }
                out[i] += filter.process(white()) * e;
            }
        }

        double white() {
            long x = rng;
            x ^= x << 13;
            x ^= x >>> 7;
            x ^= x << 17;
            rng = x;
            return (x >>> 11) * (2.0 / (1L << 53)) - 1.0;
        }
    }

    /**
     * Endless low wind: looping white noise through a low-pass whose cutoff is
     * swept by a 0.07 Hz LFO (320 ± 180 Hz), at constant low gain.
     */
    private static final class AmbientVoice extends Voice {
        static final double GAIN = 0.045;
        final Biquad filter = new Biquad();
        double t;
        long rng = seed();

        @Override
        void render(double[] out, int n) {
            for (int i = 0; i < n; i++) {
                double fc = 320.0 + 180.0 * Math.sin(2.0 * Math.PI * 0.07 * t);
                filter.set(fc, 1.0, true);
                long x = rng;
                x ^= x << 13;
                x ^= x >>> 7;
                x ^= x << 17;
                rng = x;
                double w = (x >>> 11) * (2.0 / (1L << 53)) - 1.0;
                out[i] += filter.process(w) * GAIN;
                t += DT;
            }
        }
    }

    /** RBJ-cookbook biquad (matches WebAudio's BiquadFilterNode shapes). */
    private static final class Biquad {
        double b0, b1, b2, a1, a2, z1, z2;

        void set(double fc, double q, boolean lowpass) {
            fc = Math.max(1.0, Math.min(fc, SR * 0.45));
            q = Math.max(q, 0.05);
            double w = 2.0 * Math.PI * fc / SR;
            double cs = Math.cos(w);
            double alpha = Math.sin(w) / (2.0 * q);
            double a0 = 1.0 + alpha;
            if (lowpass) {
                b0 = (1.0 - cs) * 0.5 / a0;
                b1 = (1.0 - cs) / a0;
                b2 = b0;
            } else { // band-pass, constant 0 dB peak gain
                b0 = alpha / a0;
                b1 = 0.0;
                b2 = -alpha / a0;
            }
            a1 = -2.0 * cs / a0;
            a2 = (1.0 - alpha) / a0;
        }

        double process(double x) {
            // transposed direct form II
            double y = b0 * x + z1;
            z1 = b1 * x - a1 * y + z2;
            z2 = b2 * x - a2 * y;
            return y;
        }
    }
}
