package aeternum;

import com.jme3.math.FastMath;
import com.jme3.math.Quaternion;

/**
 * Stateless pose functions (port of web/src/characters.js). Entities call
 * {@link #reset} each frame, then layer one pose on top.
 */
public final class Poses {

    private Poses() {}

    private static final Quaternion Q = new Quaternion();

    private static float smooth(float t) { return t * t * (3f - 2f * t); }

    private static float clamp01(float t) { return FastMath.clamp(t, 0f, 1f); }

    /** Remaps phase into [a,b], smoothed. */
    private static float seg(float t, float a, float b) {
        return smooth(clamp01((t - a) / (b - a)));
    }

    private static void rot(com.jme3.scene.Node n, float rx, float ry, float rz) {
        n.setLocalRotation(Q.fromAngles(rx, ry, rz));
    }

    public static void reset(Rig r) {
        rot(r.spinner, 0, 0, 0);
        r.inner.setLocalTranslation(0f, -0.9f, 0f);
        rot(r.inner, 0, 0, 0);
        rot(r.body, 0, 0, 0);
        r.body.setLocalTranslation(0f, 0.95f, 0f);
        rot(r.headG, 0, 0, 0);
        rot(r.lArm, 0, 0, 0);
        rot(r.rArm, 0, 0, 0);
        rot(r.lLeg, 0, 0, 0);
        rot(r.rLeg, 0, 0, 0);
    }

    public static void idle(Rig r, float time) {
        r.body.setLocalTranslation(0f, 0.95f + FastMath.sin(time * 1.7f) * 0.012f, 0f);
        rot(r.lArm, 0.06f + FastMath.sin(time * 1.7f) * 0.03f, 0f, 0.08f);
        rot(r.rArm, 0.06f + FastMath.cos(time * 1.5f) * 0.03f, 0f, -0.08f);
    }

    /** cycle advances with distance travelled; intensity 0..1 (walk -> run). */
    public static void walk(Rig r, float cycle, float intensity) {
        float a = 0.55f + intensity * 0.5f;
        float s = FastMath.sin(cycle), c = FastMath.sin(cycle + FastMath.PI);
        rot(r.lLeg, s * a, 0, 0);
        rot(r.rLeg, c * a, 0, 0);
        rot(r.lArm, c * a * 0.7f, 0, 0);
        rot(r.rArm, s * a * 0.7f, 0, 0);
        r.body.setLocalTranslation(0f,
                0.95f + FastMath.abs(FastMath.cos(cycle)) * 0.05f * (0.5f + intensity), 0f);
        rot(r.body, 0.08f * intensity, 0, 0);
    }

    /** kind: "light1" | "light2" | "heavy"; phase 0..1. */
    public static void attack(Rig r, float phase, String kind) {
        switch (kind) {
            case "light1" -> { // diagonal slash from upper right
                float w = seg(phase, 0f, 0.34f), st = seg(phase, 0.34f, 0.52f), rec = seg(phase, 0.6f, 1f);
                rot(r.rArm, -2.3f * w + 3.1f * st - 0.8f * rec, 0f, -0.7f * w + 1.0f * st - 0.3f * rec);
                rot(r.body, 0.25f * st - 0.25f * rec, 0.5f * w - 1.0f * st + 0.5f * rec, 0f);
            }
            case "light2" -> { // backhand sweep
                float w = seg(phase, 0f, 0.3f), st = seg(phase, 0.3f, 0.5f), rec = seg(phase, 0.58f, 1f);
                rot(r.rArm, -1.1f * w + 1.6f * st - 0.5f * rec, 0f, 1.3f * w - 2.2f * st + 0.9f * rec);
                rot(r.body, 0f, -0.6f * w + 1.2f * st - 0.6f * rec, 0f);
            }
            default -> { // heavy overhead smash
                float w = seg(phase, 0f, 0.42f), st = seg(phase, 0.42f, 0.58f), rec = seg(phase, 0.7f, 1f);
                rot(r.rArm, -2.9f * w + 3.9f * st - 1.0f * rec, 0f, 0f);
                rot(r.lArm, -1.4f * w + 1.8f * st - 0.4f * rec, 0f, 0f);
                rot(r.body, -0.25f * w + 0.65f * st - 0.4f * rec, 0f, 0f);
                r.body.setLocalTranslation(0f, 0.95f - 0.12f * st + 0.12f * rec, 0f);
            }
        }
    }

    public static void roll(Rig r, float phase) {
        rot(r.spinner, smooth(phase) * FastMath.TWO_PI, 0f, 0f);
        rot(r.lLeg, 1.5f, 0, 0);
        rot(r.rLeg, 1.3f, 0, 0);
        rot(r.lArm, -1.1f, 0, 0);
        rot(r.rArm, -1.1f, 0, 0);
        rot(r.body, 0.5f, 0, 0);
        r.inner.setLocalTranslation(0f, -0.72f, 0f); // tighten the ball
    }

    public static void stagger(Rig r, float phase) {
        float k = FastMath.sin(FastMath.PI * clamp01(phase));
        rot(r.body, -0.45f * k, 0, 0);
        rot(r.headG, -0.3f * k, 0, 0);
        rot(r.lArm, 0, 0, 0.5f * k);
        rot(r.rArm, 0, 0, -0.5f * k);
    }

    public static void death(Rig r, float phase) {
        float k = smooth(clamp01(phase));
        rot(r.spinner, -k * FastMath.HALF_PI, 0f, 0f); // fall on the back
        r.inner.setLocalTranslation(0f, -0.9f + k * 0.25f, 0f);
        rot(r.lArm, 0, 0, 0.9f * k);
        rot(r.rArm, 0, 0, -0.9f * k);
        rot(r.lLeg, 0.2f * k, 0, 0);
        rot(r.rLeg, -0.15f * k, 0, 0);
    }

    public static void drink(Rig r, float phase) {
        float up = FastMath.sin(FastMath.PI * clamp01(phase));
        rot(r.lArm, -2.2f * up, 0, 0);
        rot(r.headG, -0.35f * up, 0, 0);
    }

    /** Kneeling at the shrine. */
    public static void rest(Rig r) {
        rot(r.rLeg, -1.5f, 0, 0);
        rot(r.lLeg, 1.0f, 0, 0);
        r.inner.setLocalTranslation(0f, -1.35f, 0f);
        rot(r.body, 0.25f, 0, 0);
        rot(r.headG, 0.4f, 0, 0);
        rot(r.rArm, 0.3f, 0, 0);
        rot(r.lArm, 0.3f, 0, 0);
    }

    // ---------------------------------------------------------- boss poses

    /** Three alternating wide slashes. */
    public static void bossCombo(Rig r, float phase, int step) {
        if (step == 0) attack(r, phase, "light1");
        else if (step == 1) attack(r, phase, "light2");
        else attack(r, phase, "heavy");
    }

    public static void bossCharge(Rig r, float phase) {
        if (phase < 0.3f) { // windup: rear back, sword trailing
            float w = seg(phase, 0f, 0.3f);
            rot(r.body, -0.3f * w, 0, 0);
            rot(r.rArm, -1.8f * w, 0f, -0.9f * w);
        } else { // lunge: lean forward hard, blade extended
            float k = seg(phase, 0.3f, 0.45f);
            rot(r.body, -0.3f + 0.75f * k, 0, 0);
            rot(r.rArm, -1.8f + 0.6f * k, 0f, -0.9f + 0.9f * k);
            rot(r.lArm, 0.8f * k, 0, 0);
            float run = FastMath.sin(phase * 40f) * 0.8f;
            rot(r.lLeg, run, 0, 0);
            rot(r.rLeg, -run, 0, 0);
        }
    }

    public static void bossSlam(Rig r, float phase) {
        if (phase < 0.45f) { // crouch + leap
            float w = seg(phase, 0f, 0.2f), air = seg(phase, 0.2f, 0.45f);
            r.body.setLocalTranslation(0f, 0.95f - 0.25f * w + 0.1f * air, 0f);
            rot(r.lLeg, 0.9f * w, 0, 0);
            rot(r.rLeg, 0.9f * w, 0, 0);
            rot(r.rArm, -2.8f * (w + air) / 2f, 0, 0);
            rot(r.lArm, -2.8f * (w + air) / 2f, 0, 0);
        } else { // crash down
            float k = seg(phase, 0.45f, 0.55f);
            rot(r.rArm, -2.8f + 4.0f * k, 0, 0);
            rot(r.lArm, -2.8f + 4.0f * k, 0, 0);
            rot(r.body, 0.6f * k, 0, 0);
            r.body.setLocalTranslation(0f, 0.95f - 0.3f * k, 0f);
        }
    }

    /** Boss roar; k is a 0..1 envelope. */
    public static void shout(Rig r, float k) {
        rot(r.body, -0.35f * k, 0, 0);
        rot(r.headG, -0.4f * k, 0, 0);
        rot(r.rArm, -2.6f * k, 0, 0);
        rot(r.lArm, -1.2f * k, 0, 0);
    }
}
