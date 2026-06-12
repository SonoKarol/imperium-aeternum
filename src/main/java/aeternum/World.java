package aeternum;

import com.jme3.light.DirectionalLight;
import com.jme3.material.Material;
import com.jme3.material.RenderState;
import com.jme3.math.ColorRGBA;
import com.jme3.math.FastMath;
import com.jme3.math.Quaternion;
import com.jme3.math.Vector2f;
import com.jme3.math.Vector3f;
import com.jme3.renderer.queue.RenderQueue;
import com.jme3.scene.Geometry;
import com.jme3.scene.Mesh;
import com.jme3.scene.Node;
import com.jme3.scene.Spatial;
import com.jme3.scene.VertexBuffer;
import com.jme3.scene.shape.Box;
import com.jme3.scene.shape.Cylinder;
import com.jme3.scene.shape.Quad;
import com.jme3.scene.shape.Sphere;
import com.jme3.util.BufferUtils;
import com.jme3.util.TangentBinormalGenerator;

import java.util.ArrayList;
import java.util.List;

/**
 * Procedural environment (port of web/src/world.js): analytic terrain with a
 * lake, the Via Sacra, colonnades, statues, the Sacrarium, a ruined temple,
 * forum walls, the colosseum arena and vegetation. Also owns the circle-based
 * collision system.
 */
public class World {

    public final List<float[]> colliders = new ArrayList<>(); // {x, z, r}
    public Spatial fogWall;
    public Vector3f shrinePos;
    public DirectionalLight sun;

    private final GameCtx ctx;
    private final Node node = new Node("world");
    private Material fogMat;
    private Material rockMat;

    // deterministic placement
    private int seed = 1027;

    private float rand() {
        seed = (int) ((seed * 16807L) % 2147483647L);
        return (seed - 1) / 2147483646f;
    }

    public World(GameCtx ctx) {
        this.ctx = ctx;
        ctx.rootNode.attachChild(node);

        sun = new DirectionalLight(
                new Vector3f(0.25f, -0.28f, 0.93f).normalizeLocal(),
                new ColorRGBA(1f, 0.62f, 0.38f, 1f).mult(3.2f));
        ctx.rootNode.addLight(sun);

        // PBR ignores AmbientLight and the IBL probe is weak on vertical faces:
        // shadowless directional fills stand in for sky light and ground bounce.
        DirectionalLight skyFill = new DirectionalLight(
                new Vector3f(-0.35f, -0.75f, -0.55f).normalizeLocal(),
                new ColorRGBA(0.5f, 0.55f, 0.72f, 1f).mult(1.1f));
        DirectionalLight bounce = new DirectionalLight(
                new Vector3f(-0.2f, 0.85f, -0.5f).normalizeLocal(),
                new ColorRGBA(0.6f, 0.48f, 0.42f, 1f).mult(0.55f));
        ctx.rootNode.addLight(skyFill);
        ctx.rootNode.addLight(bounce);

        shrinePos = new Vector3f(C.SHRINE.x, getHeight(C.SHRINE.x, C.SHRINE.z), C.SHRINE.z);

        // Rock030's albedo is very dark (~72/255); boost it towards travertine
        rockMat = ctx.tex.set("Rock030");
        rockMat.setColor("BaseColor", new ColorRGBA(2.3f, 2.15f, 1.9f, 1f));

        buildTerrain();
        buildRoad();
        buildShrine();
        buildColosseum();
        buildTemple(-38f, -30f);
        buildForum(34f, -22f);
        buildVegetation();
        buildFogWall();
    }

    // ================================================================ height

    /** Analytic terrain height - the mesh and all gameplay use this. */
    public float getHeight(float x, float z) {
        float h = 1.1f * FastMath.sin(x * 0.045f) * FastMath.cos(z * 0.038f)
                + 0.7f * FastMath.sin(x * 0.11f + 1.7f) * FastMath.sin(z * 0.09f)
                + 0.35f * FastMath.sin((x + z) * 0.21f);
        h *= (1f - flatness(x, z));
        // lake depression
        float dl = FastMath.sqrt(sq(x - C.LAKE_X) + sq(z - C.LAKE_Z));
        if (dl < C.LAKE_R + 6f) {
            float k = smooth(FastMath.clamp(1f - dl / (C.LAKE_R + 6f), 0f, 1f));
            h = FastMath.interpolateLinear(k, h, -3.5f);
        }
        return h;
    }

    /** 1 = gameplay-flat, 0 = free hills. */
    private float flatness(float x, float z) {
        float f = 0f;
        // via corridor from the shrine to the arena gate
        f = Math.max(f, band(Math.abs(x), 10f, 18f) * band(0f, 1f, 1f) * corridorZ(z));
        // arena + surroundings
        f = Math.max(f, disc(x, z, C.ARENA_X, C.ARENA_Z, C.ARENA_R_OUT + 4f, 8f));
        // shrine, forum, temple
        f = Math.max(f, disc(x, z, C.SHRINE.x, C.SHRINE.z, 9f, 7f));
        f = Math.max(f, disc(x, z, 34f, -22f, 13f, 7f));
        f = Math.max(f, disc(x, z, -38f, -30f, 15f, 7f));
        return f;
    }

    private float corridorZ(float z) {
        // 1 between -58..24, fading outside
        if (z > 24f) return Math.max(0f, 1f - (z - 24f) / 10f);
        if (z < -58f) return Math.max(0f, 1f - (-58f - z) / 10f);
        return 1f;
    }

    private static float band(float v, float inner, float outer) {
        if (v <= inner) return 1f;
        if (v >= outer) return 0f;
        return 1f - smooth((v - inner) / (outer - inner));
    }

    private static float disc(float x, float z, float cx, float cz, float r, float fade) {
        float d = FastMath.sqrt(sq(x - cx) + sq(z - cz));
        return band(d, r, r + fade);
    }

    private static float sq(float v) { return v * v; }

    private static float smooth(float t) { return t * t * (3f - 2f * t); }

    // ============================================================== terrain

    private void buildTerrain() {
        int n = 121;               // vertices per side
        float size = 240f, step = size / (n - 1), half = size / 2f;
        Vector3f[] pos = new Vector3f[n * n];
        Vector3f[] norm = new Vector3f[n * n];
        Vector2f[] uv = new Vector2f[n * n];
        for (int j = 0; j < n; j++) {
            for (int i = 0; i < n; i++) {
                float x = -half + i * step, z = -half + j * step;
                int k = j * n + i;
                pos[k] = new Vector3f(x, getHeight(x, z), z);
                // analytic normal by central differences
                float e = 0.6f;
                float hx = getHeight(x + e, z) - getHeight(x - e, z);
                float hz = getHeight(x, z + e) - getHeight(x, z - e);
                norm[k] = new Vector3f(-hx / (2 * e), 1f, -hz / (2 * e)).normalizeLocal();
                uv[k] = new Vector2f(x / 3.2f, z / 3.2f);
            }
        }
        int[] idx = new int[(n - 1) * (n - 1) * 6];
        int t = 0;
        for (int j = 0; j < n - 1; j++) {
            for (int i = 0; i < n - 1; i++) {
                int a = j * n + i, b = a + 1, c = a + n, d = c + 1;
                idx[t++] = a; idx[t++] = c; idx[t++] = b;
                idx[t++] = b; idx[t++] = c; idx[t++] = d;
            }
        }
        Mesh m = new Mesh();
        m.setBuffer(VertexBuffer.Type.Position, 3, BufferUtils.createFloatBuffer(pos));
        m.setBuffer(VertexBuffer.Type.Normal, 3, BufferUtils.createFloatBuffer(norm));
        m.setBuffer(VertexBuffer.Type.TexCoord, 2, BufferUtils.createFloatBuffer(uv));
        m.setBuffer(VertexBuffer.Type.Index, 3, BufferUtils.createIntBuffer(idx));
        m.updateBound();
        Geometry ground = new Geometry("terrain", m);
        Material groundMat = ctx.tex.set("Grass004");
        groundMat.setColor("BaseColor", new ColorRGBA(0.72f, 0.78f, 0.55f, 1f)); // olive cast
        ground.setMaterial(groundMat);
        ground.setShadowMode(RenderQueue.ShadowMode.Receive);
        TangentBinormalGenerator.generate(ground);
        node.attachChild(ground);

        // dry-dirt arena floor + a few worn patches along the via
        addDisc(C.ARENA_X, C.ARENA_Z, C.ARENA_R_IN + 0.6f, "Ground037", 7f);
        addDisc(0f, 14f, 6.5f, "Ground037", 5f);
        addDisc(0f, -20f, 7.5f, "Ground037", 6f);
        addDisc(0f, -45f, 8f, "Ground037", 6f);
        addDisc(34f, -22f, 9f, "Ground037", 6f);
    }

    /** Flat textured disc lying on the terrain (planar tiled UVs). */
    private void addDisc(float cx, float cz, float r, String texSet, float uvTile) {
        int segs = 36;
        Vector3f[] pos = new Vector3f[segs + 2];
        Vector3f[] norm = new Vector3f[segs + 2];
        Vector2f[] uv = new Vector2f[segs + 2];
        pos[0] = new Vector3f(cx, getHeight(cx, cz) + 0.14f, cz);
        norm[0] = Vector3f.UNIT_Y;
        uv[0] = new Vector2f(cx / uvTile, cz / uvTile);
        for (int i = 0; i <= segs; i++) {
            float a = i * FastMath.TWO_PI / segs;
            float x = cx + FastMath.cos(a) * r, z = cz + FastMath.sin(a) * r;
            pos[i + 1] = new Vector3f(x, getHeight(x, z) + 0.14f, z);
            norm[i + 1] = Vector3f.UNIT_Y;
            uv[i + 1] = new Vector2f(x / uvTile, z / uvTile);
        }
        int[] idx = new int[segs * 3];
        for (int i = 0; i < segs; i++) {
            idx[i * 3] = 0; idx[i * 3 + 1] = i + 2; idx[i * 3 + 2] = i + 1;
        }
        Mesh m = new Mesh();
        m.setBuffer(VertexBuffer.Type.Position, 3, BufferUtils.createFloatBuffer(pos));
        m.setBuffer(VertexBuffer.Type.Normal, 3, BufferUtils.createFloatBuffer(norm));
        m.setBuffer(VertexBuffer.Type.TexCoord, 2, BufferUtils.createFloatBuffer(uv));
        m.setBuffer(VertexBuffer.Type.Index, 3, BufferUtils.createIntBuffer(idx));
        m.updateBound();
        Geometry g = new Geometry("disc", m);
        g.setMaterial(ctx.tex.set(texSet));
        g.setShadowMode(RenderQueue.ShadowMode.Receive);
        TangentBinormalGenerator.generate(g);
        node.attachChild(g);
    }

    // =============================================================== helpers

    /** Box geometry with ~1 texture tile per `uvTile` metres. */
    private Geometry box(float w, float h, float d, Material mat, float uvTile) {
        Box b = new Box(w / 2f, h / 2f, d / 2f);
        b.scaleTextureCoordinates(new Vector2f(
                Math.max(w, d) / uvTile, h / uvTile));
        Geometry g = new Geometry("box", b);
        g.setMaterial(mat);
        g.setShadowMode(RenderQueue.ShadowMode.CastAndReceive);
        TangentBinormalGenerator.generate(g);
        return g;
    }

    private Geometry cylY(float rTop, float rBottom, float h, int samples, Material mat, float uvTile) {
        Cylinder c = new Cylinder(2, samples, rTop, rBottom, h, true, false);
        c.scaleTextureCoordinates(new Vector2f(FastMath.TWO_PI * rBottom / uvTile, h / uvTile));
        Geometry g = new Geometry("cyl", c);
        g.setMaterial(mat);
        g.setShadowMode(RenderQueue.ShadowMode.CastAndReceive);
        g.rotate(FastMath.HALF_PI, 0f, 0f);
        TangentBinormalGenerator.generate(g);
        return g;
    }

    private void place(Spatial s, float x, float z, float yOff, float yaw) {
        s.setLocalTranslation(x, getHeight(x, z) + yOff, z);
        if (yaw != 0f) s.rotate(0f, yaw, 0f);
        node.attachChild(s);
    }

    // ================================================================= road

    private void buildRoad() {
        Material paving = ctx.tex.set("PavingStones070");
        for (float z = 8f; z > -53f; z -= 1.9f) {
            for (int i = -1; i <= 1; i++) {
                if (rand() < 0.12f) continue; // missing slabs
                Geometry slab = box(2.4f, 0.12f, 1.7f, paving, 2.4f);
                place(slab, i * 2.5f + (rand() - 0.5f) * 0.3f, z + (rand() - 0.5f) * 0.3f,
                        0.06f + rand() * 0.04f, (rand() - 0.5f) * 0.1f);
            }
        }
        // colonnades flanking the via
        Material marble = ctx.tex.set("Marble012");
        for (float z = 0f; z > -50f; z -= 12f) {
            addColumn(marble, -6.5f, z, 2f + rand() * 4f, (rand() - 0.5f) * 0.08f);
            addColumn(marble, 6.5f, z - 6f, 2f + rand() * 4f, (rand() - 0.5f) * 0.08f);
        }
        addStatue(-5.5f, -24f, 0.7f);
        addStatue(5.5f, -38f, -0.7f);
        addBrazier(-4f, -12f);
        addBrazier(4f, -44f);
    }

    private void addColumn(Material marble, float x, float z, float shaftH, float tilt) {
        Node g = new Node("column");
        Geometry base = box(1.5f, 0.4f, 1.5f, marble, 1.5f);
        base.setLocalTranslation(0f, 0.2f, 0f);
        g.attachChild(base);
        Geometry shaft = cylY(0.42f, 0.48f, shaftH, 22, marble, 1.6f);
        shaft.setLocalTranslation(0f, 0.4f + shaftH / 2f, 0f);
        g.attachChild(shaft);
        if (shaftH > 4.5f) {
            Geometry cap = box(1.3f, 0.35f, 1.3f, marble, 1.3f);
            cap.setLocalTranslation(0f, 0.4f + shaftH + 0.17f, 0f);
            g.attachChild(cap);
        }
        if (tilt != 0f) g.rotate(0f, 0f, tilt);
        place(g, x, z, 0f, 0f);
        colliders.add(new float[]{x, z, 0.75f});
    }

    private void addStatue(float x, float z, float faceYaw) {
        Material marble = ctx.tex.set("Marble012");
        Geometry ped = box(1.6f, 0.9f, 1.6f, marble, 1.6f);
        place(ped, x, z, 0.45f, 0f);
        Rig.Options o = new Rig.Options();
        o.marble = true;
        o.helmet = "centurion";
        o.shield = true;
        o.scale = 1.25f;
        Rig rig = Rig.humanoid(ctx, o);
        Poses.reset(rig);
        rig.rArm.setLocalRotation(new Quaternion().fromAngles(-2.6f, 0f, 0f));
        place(rig.root, x, z, 0.9f, faceYaw);
        colliders.add(new float[]{x, z, 1.0f});
    }

    private void addBrazier(float x, float z) {
        Material iron = ctx.tex.color(new ColorRGBA(0.2f, 0.2f, 0.22f, 1f), 0.8f, 0.45f);
        Node g = new Node("brazier");
        Geometry pole = cylY(0.08f, 0.12f, 1.4f, 10, iron, 1f);
        pole.setLocalTranslation(0f, 0.7f, 0f);
        g.attachChild(pole);
        Geometry bowl = cylY(0.4f, 0.2f, 0.3f, 12, iron, 1f);
        bowl.setLocalTranslation(0f, 1.5f, 0f);
        g.attachChild(bowl);
        Node fire = new Node("fire");
        fire.setLocalTranslation(0f, 1.7f, 0f);
        g.attachChild(fire);
        ctx.fx.attachFire(fire, false);
        place(g, x, z, 0f, 0f);
        colliders.add(new float[]{x, z, 0.45f});
    }

    // =============================================================== shrine

    private void buildShrine() {
        float x = C.SHRINE.x, z = C.SHRINE.z;
        Material stone = rockMat;
        Material iron = ctx.tex.color(new ColorRGBA(0.22f, 0.22f, 0.25f, 1f), 0.8f, 0.4f);
        Node g = new Node("shrine");
        Geometry dais = cylY(2.6f, 2.9f, 0.35f, 28, stone, 2.5f);
        dais.setLocalTranslation(0f, 0.17f, 0f);
        g.attachChild(dais);
        Geometry ped = cylY(0.5f, 0.65f, 1.0f, 14, stone, 1.2f);
        ped.setLocalTranslation(0f, 0.85f, 0f);
        g.attachChild(ped);
        Geometry bowl = cylY(0.75f, 0.45f, 0.4f, 16, iron, 1.5f);
        bowl.setLocalTranslation(0f, 1.5f, 0f);
        g.attachChild(bowl);
        Node fire = new Node("fire");
        fire.setLocalTranslation(0f, 1.8f, 0f);
        g.attachChild(fire);
        ctx.fx.attachFire(fire, true);
        place(g, x, z, 0f, 0f);
        colliders.add(new float[]{x, z, 1.1f});

        Material marble = ctx.tex.set("Marble012");
        for (float a = 0f; a < FastMath.TWO_PI; a += FastMath.PI / 3f) {
            if (rand() < 0.3f) continue;
            addColumn(marble, x + FastMath.cos(a) * 4.2f, z + FastMath.sin(a) * 4.2f,
                    1.2f + rand() * 2.4f, 0f);
        }
    }

    // ============================================================ colosseum

    private void buildColosseum() {
        float cx = C.ARENA_X, cz = C.ARENA_Z;
        float rMid = (C.ARENA_R_IN + C.ARENA_R_OUT) / 2f;
        Material tuff = rockMat;
        int SEG = 18;

        for (int i = 0; i < SEG; i++) {
            float a0 = i * FastMath.TWO_PI / SEG;
            float aMid = a0 + FastMath.PI / SEG;
            boolean isGate = Math.abs(C.angleDiff(aMid, C.GATE_ANGLE)) < FastMath.PI / SEG;
            boolean ruined = !isGate && rand() < 0.28f;

            float px = cx + FastMath.cos(a0) * rMid, pz = cz + FastMath.sin(a0) * rMid;
            float ph = (ruined && rand() < 0.5f) ? 3f + rand() * 3f : 9f;
            Geometry pillar = box(1.6f, ph, 1.6f, tuff, 2.2f);
            place(pillar, px, pz, ph / 2f, -a0);

            if (isGate) {
                float a1 = a0 + FastMath.TWO_PI / SEG;
                float fx = cx + FastMath.cos(a1) * rMid, fz = cz + FastMath.sin(a1) * rMid;
                Geometry flank = box(1.8f, 11f, 1.8f, tuff, 2.2f);
                place(flank, fx, fz, 5.5f, -a1);
                Geometry lintel = box(8.5f, 1.6f, 2f, tuff, 2.2f);
                place(lintel, cx + FastMath.cos(aMid) * rMid, cz + FastMath.sin(aMid) * rMid,
                        10.2f, -aMid + FastMath.HALF_PI);
                continue;
            }

            float arcLen = 2f * rMid * FastMath.sin(FastMath.PI / SEG) - 1.6f;
            float wallH = ruined ? 2.5f + rand() * 2f : 8f;
            Geometry wall = box(arcLen, wallH, 1.1f, tuff, 2.6f);
            place(wall, cx + FastMath.cos(aMid) * rMid, cz + FastMath.sin(aMid) * rMid,
                    wallH / 2f, -aMid + FastMath.HALF_PI);
            if (!ruined) {
                Geometry upper = box(arcLen, 3.2f, 1.0f, tuff, 2.6f);
                place(upper, cx + FastMath.cos(aMid) * rMid, cz + FastMath.sin(aMid) * rMid,
                        10.9f, -aMid + FastMath.HALF_PI);
            } else {
                for (int k = 0; k < 3; k++) {
                    addRock(cx + FastMath.cos(aMid) * (rMid + (rand() - 0.5f) * 4f),
                            cz + FastMath.sin(aMid) * (rMid + (rand() - 0.5f) * 4f),
                            0.5f + rand() * 0.6f, false);
                }
            }
        }
        addBrazier(cx - C.ARENA_R_IN + 3f, cz);
        addBrazier(cx + C.ARENA_R_IN - 3f, cz);
    }

    private void buildFogWall() {
        float gx = C.ARENA_X + FastMath.cos(C.GATE_ANGLE) * C.ARENA_R_IN;
        float gz = C.ARENA_Z + FastMath.sin(C.GATE_ANGLE) * C.ARENA_R_IN;
        Geometry wall = new Geometry("fogwall", new Quad(9f, 10f));
        fogMat = new Material(ctx.assets, "Common/MatDefs/Misc/Unshaded.j3md");
        fogMat.setColor("Color", new ColorRGBA(0.85f, 0.81f, 0.66f, 0.22f));
        fogMat.getAdditionalRenderState().setBlendMode(RenderState.BlendMode.Alpha);
        fogMat.getAdditionalRenderState().setFaceCullMode(RenderState.FaceCullMode.Off);
        wall.setMaterial(fogMat);
        wall.setQueueBucket(RenderQueue.Bucket.Transparent);
        wall.setShadowMode(RenderQueue.ShadowMode.Off);
        wall.setLocalTranslation(gx - 4.5f, getHeight(gx, gz), gz + 1.2f);
        wall.setCullHint(Spatial.CullHint.Always);
        node.attachChild(wall);
        fogWall = wall;
    }

    // =============================================================== temple

    private void buildTemple(float x, float z) {
        Material marble = ctx.tex.set("Marble012");
        Material tuff = rockMat;
        for (float[] c : new float[][]{{-5.5f, 0f}, {0f, 0f}, {5.5f, 0f}}) {
            colliders.add(new float[]{x + c[0], z, 5.6f});
        }
        float baseY = getHeight(x, z);
        for (int i = 0; i < 3; i++) {
            Geometry step = box(16f - i * 1.4f, 0.45f, 11f - i * 1.4f, marble, 2.8f);
            step.setLocalTranslation(x, baseY + 0.22f + i * 0.45f, z);
            node.attachChild(step);
        }
        float floorY = baseY + 3 * 0.45f;
        for (int i = 0; i < 5; i++) {
            float cxx = x - 6f + i * 3f;
            boolean intact = rand() > 0.35f;
            float h = intact ? 5f : 1f + rand() * 2.5f;
            Node col = new Node("tcol");
            Geometry shaft = cylY(0.42f, 0.48f, h, 22, marble, 1.6f);
            shaft.setLocalTranslation(0f, 0.4f + h / 2f, 0f);
            Geometry base = box(1.3f, 0.4f, 1.3f, marble, 1.3f);
            base.setLocalTranslation(0f, 0.2f, 0f);
            col.attachChild(base);
            col.attachChild(shaft);
            col.setLocalTranslation(cxx, floorY, z + 4f);
            node.attachChild(col);
        }
        Geometry back = box(14f, 5f, 0.8f, tuff, 2.6f);
        back.setLocalTranslation(x, floorY + 2.5f, z - 4f);
        node.attachChild(back);
        Geometry pediment = box(15f, 1f, 1.2f, marble, 2.8f);
        pediment.setLocalTranslation(x, floorY + 5.6f, z + 4f);
        node.attachChild(pediment);

        Rig.Options o = new Rig.Options();
        o.marble = true;
        o.scale = 1.7f;
        Rig statue = Rig.humanoid(ctx, o);
        Poses.reset(statue);
        statue.rArm.setLocalRotation(new Quaternion().fromAngles(-1.2f, 0f, 0f));
        statue.lArm.setLocalRotation(new Quaternion().fromAngles(-0.6f, 0f, 0f));
        statue.root.setLocalTranslation(x, floorY, z - 2.5f);
        statue.root.rotate(0f, FastMath.PI, 0f);
        node.attachChild(statue.root);
    }

    // ================================================================ forum

    private void buildForum(float cx, float cz) {
        Material tuff = rockMat;
        float w = 14f, d = 10f;
        float[][] segments = {
                {cx, cz - d / 2f, w, 0f},
                {cx, cz + d / 2f, w, 0f},
                {cx - w / 2f, cz, d, FastMath.HALF_PI},
                {cx + w / 2f, cz, d, FastMath.HALF_PI},
        };
        for (float[] s : segments) {
            int pieces = (int) Math.ceil(s[2] / 3f);
            for (int i = 0; i < pieces; i++) {
                if (rand() < 0.4f) continue;
                float h = 1.2f + rand() * 2.4f;
                float off = -s[2] / 2f + 1.5f + i * 3f;
                float px = s[0] + FastMath.cos(s[3]) * off;
                float pz = s[1] + FastMath.sin(s[3]) * off;
                Geometry wall = box(3f, h, 0.7f, tuff, 2.2f);
                place(wall, px, pz, h / 2f, -s[3]);
                colliders.add(new float[]{px, pz, 1.4f});
            }
        }
        Material marble = ctx.tex.set("Marble012");
        for (int i = 0; i < 5; i++) {
            addColumn(marble, cx - 6f + rand() * 12f, cz - 8f + rand() * 14f,
                    1.5f + rand() * 4.5f, (rand() - 0.5f) * 0.12f);
        }
    }

    // =========================================================== vegetation

    private void buildVegetation() {
        Material bark = ctx.tex.set("Bark007");
        Material leaf = ctx.tex.color(new ColorRGBA(0.1f, 0.16f, 0.08f, 1f), 0f, 0.95f);
        Material dead = ctx.tex.color(new ColorRGBA(0.26f, 0.21f, 0.15f, 1f), 0f, 0.9f);

        for (int i = 0; i < 46; i++) {
            float a = rand() * FastMath.TWO_PI;
            float r = 45f + rand() * 60f;
            float x = FastMath.cos(a) * r, z = FastMath.sin(a) * r;
            if (FastMath.sqrt(sq(x - C.ARENA_X) + sq(z - C.ARENA_Z)) < 32f) continue;
            if (FastMath.sqrt(sq(x - C.LAKE_X) + sq(z - C.LAKE_Z)) < C.LAKE_R + 4f) continue;
            Node g = new Node("tree");
            Geometry trunk = cylY(0.14f, 0.24f, 1.7f, 10, bark, 0.8f);
            trunk.setLocalTranslation(0f, 0.85f, 0f);
            g.attachChild(trunk);
            if (rand() < 0.7f) { // cypress: stacked tapered cones
                float h = 4f + rand() * 3f;
                for (int s = 0; s < 3; s++) {
                    float ch = h * (0.55f - s * 0.12f);
                    float cr = 0.95f - s * 0.28f;
                    Geometry cone = cylY(0.02f, cr, ch, 12, leaf, 2f);
                    cone.setLocalTranslation(0f, 1.2f + s * h * 0.28f + ch / 2f, 0f);
                    g.attachChild(cone);
                }
            } else { // dead snag
                Geometry b1 = cylY(0.05f, 0.09f, 1.4f, 8, dead, 1f);
                b1.setLocalTranslation(0.3f, 1.9f, 0f);
                b1.rotate(0f, 0f, -0.7f);
                g.attachChild(b1);
                Geometry b2 = cylY(0.04f, 0.08f, 1.1f, 8, dead, 1f);
                b2.setLocalTranslation(-0.25f, 1.7f, 0.1f);
                b2.rotate(0f, 0f, 0.9f);
                g.attachChild(b2);
            }
            place(g, x, z, -0.1f, rand() * FastMath.TWO_PI);
            colliders.add(new float[]{x, z, 0.5f});
        }

        // scattered rocks and lone columns
        float[][] spots = {{-20, 8}, {22, 2}, {-28, -8}, {40, -45}, {-15, -52}, {18, -28}, {-45, -5}, {50, -15}};
        Material marble = ctx.tex.set("Marble012");
        for (float[] s : spots) {
            if (rand() < 0.5f) addColumn(marble, s[0], s[1], 1f + rand() * 5f, (rand() - 0.5f) * 0.15f);
            else addRock(s[0], s[1], 0.8f + rand() * 1.2f, true);
        }
        // boulders ringing the lake shore
        for (int i = 0; i < 10; i++) {
            float a = rand() * FastMath.TWO_PI;
            float rr = C.LAKE_R + 1.5f + rand() * 3f;
            addRock(C.LAKE_X + FastMath.cos(a) * rr, C.LAKE_Z + FastMath.sin(a) * rr,
                    0.6f + rand() * 1.4f, true);
        }
    }

    /** Noise-displaced sphere boulder. */
    private void addRock(float x, float z, float s, boolean collide) {
        Sphere base = new Sphere(10, 14, s);
        Mesh m = base.deepClone();
        VertexBuffer pb = m.getBuffer(VertexBuffer.Type.Position);
        java.nio.FloatBuffer fb = (java.nio.FloatBuffer) pb.getData();
        Vector3f[] norms = new Vector3f[fb.limit() / 3];
        for (int i = 0; i < fb.limit() / 3; i++) {
            Vector3f v = new Vector3f(fb.get(i * 3), fb.get(i * 3 + 1), fb.get(i * 3 + 2));
            float k = 0.8f + rand() * 0.4f;
            v.multLocal(k);
            fb.put(i * 3, v.x).put(i * 3 + 1, v.y).put(i * 3 + 2, v.z);
            norms[i] = v.normalize();
        }
        pb.updateData(fb);
        m.setBuffer(VertexBuffer.Type.Normal, 3, BufferUtils.createFloatBuffer(norms));
        m.updateBound();
        Geometry g = new Geometry("rock", m);
        g.setMaterial(rockMat);
        g.setShadowMode(RenderQueue.ShadowMode.CastAndReceive);
        TangentBinormalGenerator.generate(g);
        place(g, x, z, s * 0.35f, rand() * FastMath.TWO_PI);
        if (collide) colliders.add(new float[]{x, z, s * 0.9f});
    }

    // ============================================================ collision

    /** Port of web/src/world.js resolveStatics - operates on x/z only. */
    public void resolveStatics(Vector3f pos, float radius, boolean fogLocked) {
        for (float[] c : colliders) {
            float dx = pos.x - c[0], dz = pos.z - c[1];
            float d2 = dx * dx + dz * dz;
            float minD = c[2] + radius;
            if (d2 < minD * minD && d2 > 1e-6f) {
                float d = FastMath.sqrt(d2);
                pos.x = c[0] + (dx / d) * minD;
                pos.z = c[1] + (dz / d) * minD;
            }
        }
        // colosseum ring wall with a gate sector
        float dx = pos.x - C.ARENA_X, dz = pos.z - C.ARENA_Z;
        float d = FastMath.sqrt(dx * dx + dz * dz);
        boolean inBand = d > C.ARENA_R_IN - radius && d < C.ARENA_R_OUT + radius;
        if (inBand && d > 1e-6f) {
            float ang = FastMath.atan2(dz, dx);
            boolean inGate = Math.abs(C.angleDiff(ang, C.GATE_ANGLE)) < C.GATE_HALF;
            if (!inGate || fogLocked) {
                float target = d < (C.ARENA_R_IN + C.ARENA_R_OUT) / 2f
                        ? C.ARENA_R_IN - radius : C.ARENA_R_OUT + radius;
                pos.x = C.ARENA_X + (dx / d) * target;
                pos.z = C.ARENA_Z + (dz / d) * target;
            }
        }
        // outer world boundary
        float od = FastMath.sqrt(pos.x * pos.x + pos.z * pos.z);
        if (od > C.WORLD_RADIUS) {
            pos.x *= C.WORLD_RADIUS / od;
            pos.z *= C.WORLD_RADIUS / od;
        }
    }

    // =============================================================== update

    public void update(float tpf) {
        if (fogWall.getCullHint() != Spatial.CullHint.Always) {
            fogMat.setColor("Color", new ColorRGBA(0.85f, 0.81f, 0.66f,
                    0.18f + 0.08f * FastMath.sin(ctx.time * 2.2f)));
        }
    }
}
