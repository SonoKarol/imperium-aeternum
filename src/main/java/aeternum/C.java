package aeternum;

import com.jme3.math.FastMath;
import com.jme3.math.Vector3f;

/** Global gameplay constants shared by every module. */
public final class C {
    private C() {}

    // Colosseum arena (boss area)
    public static final float ARENA_X = 0f;
    public static final float ARENA_Z = -80f;
    public static final float ARENA_R_IN = 22.5f;
    public static final float ARENA_R_OUT = 25.8f;
    public static final float GATE_ANGLE = FastMath.HALF_PI; // gate faces +Z (towards shrine)
    public static final float GATE_HALF = 0.16f;             // angular half-width of the gate

    // Shrine (checkpoint). Y is resolved against terrain at build time.
    public static final Vector3f SHRINE = new Vector3f(0f, 0f, 14f);

    // Lake next to the western temple (rendered with WaterFilter)
    public static final float LAKE_X = -62f;
    public static final float LAKE_Z = -44f;
    public static final float LAKE_R = 20f;
    public static final float WATER_LEVEL = -0.85f;

    public static final float WORLD_RADIUS = 112f;

    public static float angleDiff(float a, float b) {
        float d = a - b;
        while (d > FastMath.PI) d -= FastMath.TWO_PI;
        while (d < -FastMath.PI) d += FastMath.TWO_PI;
        return d;
    }
}
