// Fully procedural audio: every sound is synthesized with WebAudio, no files.
export class AudioFX {
  constructor() {
    this.ctx = null;
    this.master = null;
    this.noiseBuf = null;
    this.bossTimer = null;
    this.ambientNodes = [];
  }

  // Must be called from a user gesture (click on title screen).
  init() {
    if (this.ctx) { this.ctx.resume(); return; }
    const AC = window.AudioContext || window.webkitAudioContext;
    if (!AC) return;
    this.ctx = new AC();
    this.master = this.ctx.createGain();
    this.master.gain.value = 0.5;
    this.master.connect(this.ctx.destination);

    // Cached white-noise buffer (2s) reused by every noise-based effect.
    const len = this.ctx.sampleRate * 2;
    this.noiseBuf = this.ctx.createBuffer(1, len, this.ctx.sampleRate);
    const data = this.noiseBuf.getChannelData(0);
    for (let i = 0; i < len; i++) data[i] = Math.random() * 2 - 1;

    this.startAmbient();
  }

  get t() { return this.ctx.currentTime; }

  tone(freq, dur, { type = 'sine', gain = 0.2, slideTo = null, attack = 0.01 } = {}) {
    if (!this.ctx) return;
    const o = this.ctx.createOscillator();
    const g = this.ctx.createGain();
    o.type = type;
    o.frequency.setValueAtTime(freq, this.t);
    if (slideTo !== null) o.frequency.exponentialRampToValueAtTime(Math.max(slideTo, 1), this.t + dur);
    g.gain.setValueAtTime(0.0001, this.t);
    g.gain.exponentialRampToValueAtTime(gain, this.t + attack);
    g.gain.exponentialRampToValueAtTime(0.0001, this.t + dur);
    o.connect(g).connect(this.master);
    o.start();
    o.stop(this.t + dur + 0.05);
  }

  noise(dur, { freq = 1000, q = 1, gain = 0.25, slideTo = null, type = 'bandpass' } = {}) {
    if (!this.ctx) return;
    const src = this.ctx.createBufferSource();
    src.buffer = this.noiseBuf;
    src.loop = true;
    const f = this.ctx.createBiquadFilter();
    f.type = type;
    f.frequency.setValueAtTime(freq, this.t);
    if (slideTo !== null) f.frequency.exponentialRampToValueAtTime(Math.max(slideTo, 1), this.t + dur);
    f.Q.value = q;
    const g = this.ctx.createGain();
    g.gain.setValueAtTime(0.0001, this.t);
    g.gain.exponentialRampToValueAtTime(gain, this.t + 0.01);
    g.gain.exponentialRampToValueAtTime(0.0001, this.t + dur);
    src.connect(f).connect(g).connect(this.master);
    src.start();
    src.stop(this.t + dur + 0.05);
  }

  // --- combat ---
  swing()      { this.noise(0.18, { freq: 2400, slideTo: 500, q: 2, gain: 0.16 }); }
  swingHeavy() { this.noise(0.3,  { freq: 1600, slideTo: 280, q: 2, gain: 0.22 }); }
  hit() {
    this.noise(0.1, { freq: 3000, slideTo: 900, q: 1, gain: 0.3 });
    this.tone(140, 0.14, { type: 'square', gain: 0.18, slideTo: 60 });
  }
  clang() {
    this.tone(1900, 0.25, { type: 'triangle', gain: 0.12, slideTo: 1500 });
    this.noise(0.08, { freq: 4000, q: 4, gain: 0.15 });
  }
  hurt() {
    this.tone(220, 0.25, { type: 'sawtooth', gain: 0.2, slideTo: 90 });
    this.noise(0.15, { freq: 800, slideTo: 200, gain: 0.2 });
  }
  roll() { this.noise(0.25, { freq: 500, slideTo: 150, q: 0.8, gain: 0.14, type: 'lowpass' }); }
  step() { this.noise(0.05, { freq: 300, q: 0.5, gain: 0.04, type: 'lowpass' }); }

  enemyDie() {
    this.tone(160, 0.6, { type: 'sawtooth', gain: 0.16, slideTo: 40 });
    this.noise(0.4, { freq: 600, slideTo: 100, gain: 0.18 });
  }

  // --- player feedback ---
  heal() {
    this.tone(520, 0.5, { gain: 0.12, slideTo: 780 });
    this.tone(660, 0.7, { gain: 0.1, slideTo: 990, attack: 0.2 });
  }
  pickup() {
    this.tone(740, 0.18, { gain: 0.12 });
    this.tone(1110, 0.3, { gain: 0.1, attack: 0.06 });
  }
  rest() {
    [262, 330, 392, 523].forEach((f, i) =>
      setTimeout(() => this.tone(f, 1.4, { gain: 0.09, attack: 0.1 }), i * 220));
  }
  levelup() {
    [392, 494, 587, 784].forEach((f, i) =>
      setTimeout(() => this.tone(f, 0.5, { gain: 0.11 }), i * 110));
  }
  death() {
    this.tone(110, 2.4, { type: 'sawtooth', gain: 0.2, slideTo: 30, attack: 0.3 });
    this.tone(116, 2.4, { type: 'sawtooth', gain: 0.15, slideTo: 33, attack: 0.3 });
  }
  victory() {
    [523, 659, 784, 1047, 784, 1047].forEach((f, i) =>
      setTimeout(() => this.tone(f, 0.9, { gain: 0.12, attack: 0.05 }), i * 260));
  }
  bossRoar() {
    this.tone(75, 1.4, { type: 'sawtooth', gain: 0.3, slideTo: 45, attack: 0.15 });
    this.noise(1.2, { freq: 350, slideTo: 90, q: 1.5, gain: 0.25 });
  }

  // --- ambient wind, runs forever at low volume ---
  startAmbient() {
    const src = this.ctx.createBufferSource();
    src.buffer = this.noiseBuf;
    src.loop = true;
    const f = this.ctx.createBiquadFilter();
    f.type = 'lowpass';
    f.frequency.value = 320;
    const g = this.ctx.createGain();
    g.gain.value = 0.045;
    // Slow wind swell via LFO on the filter.
    const lfo = this.ctx.createOscillator();
    lfo.frequency.value = 0.07;
    const lfoG = this.ctx.createGain();
    lfoG.gain.value = 180;
    lfo.connect(lfoG).connect(f.frequency);
    src.connect(f).connect(g).connect(this.master);
    src.start();
    lfo.start();
    this.ambientNodes = [src, lfo];
  }

  // --- boss music: war-drum ostinato + low drone ---
  startBossMusic() {
    if (!this.ctx || this.bossTimer) return;
    const beat = () => {
      this.tone(95, 0.4, { type: 'sine', gain: 0.3, slideTo: 38 });
      this.noise(0.12, { freq: 200, q: 1, gain: 0.12, type: 'lowpass' });
      setTimeout(() => this.tone(95, 0.3, { type: 'sine', gain: 0.18, slideTo: 38 }), 360);
    };
    beat();
    this.tone(55, 3, { type: 'sawtooth', gain: 0.05, attack: 0.6 });
    this.bossTimer = setInterval(() => {
      beat();
      this.tone(55, 3, { type: 'sawtooth', gain: 0.05, attack: 0.6 });
      this.tone(65.4, 3, { type: 'sawtooth', gain: 0.035, attack: 0.6 });
    }, 1440);
  }

  stopBossMusic() {
    if (this.bossTimer) { clearInterval(this.bossTimer); this.bossTimer = null; }
  }
}
