package aeternum;

import com.jme3.material.Material;
import com.jme3.material.RenderState;
import com.jme3.math.ColorRGBA;
import com.jme3.math.FastMath;
import com.jme3.math.Vector2f;
import com.jme3.math.Vector3f;
import com.jme3.renderer.queue.RenderQueue;
import com.jme3.scene.Geometry;
import com.jme3.scene.Mesh;
import com.jme3.scene.Node;
import com.jme3.scene.VertexBuffer;
import com.jme3.scene.shape.Box;
import com.jme3.scene.shape.Cylinder;
import com.jme3.scene.shape.Sphere;
import com.jme3.util.BufferUtils;
import com.jme3.util.TangentBinormalGenerator;

import java.util.ArrayList;
import java.util.List;

/**
 * Procedural humanoid rig (port of web/src/characters.js).
 * Hierarchy: root -> spinner(y=.9) -> inner(y=-.9) -> body/limbs.
 * `spinner` pivots at hip height so dodge-rolls read as somersaults while
 * `inner` cancels the offset so pose code works in feet-space.
 */
public class Rig {

    public Node root, spinner, inner, body, headG, lArm, rArm, lLeg, rLeg, swordG, shieldG;
    public final List<Geometry> meshes = new ArrayList<>();
    public float scale;

    /** Mirrors the JS buildHumanoid options. */
    public static class Options {
        public ColorRGBA skin = rgb(0xb98a64);
        public ColorRGBA tunic = rgb(0x8b2020);
        public ColorRGBA crest = rgb(0xa01818);
        public String helmet = "none"; // none | legionary | centurion
        public boolean shield = false;
        public float swordLen = 0.8f;
        public float scale = 1f;
        public boolean marble = false;

        static ColorRGBA rgb(int hex) {
            return new ColorRGBA(((hex >> 16) & 0xff) / 255f,
                    ((hex >> 8) & 0xff) / 255f, (hex & 0xff) / 255f, 1f);
        }
    }

    // per-rig materials so setFade never affects other rigs
    private final List<Material> mats = new ArrayList<>();
    private boolean faded;

    private Rig() {}

    public static Rig humanoid(GameCtx ctx, Options o) {
        Rig r = new Rig();
        r.scale = o.scale;

        Material mSkin, mTunic, mArmor, mTrim, mCrest, mIron, mLeather, mCloth;
        if (o.marble) {
            Material marble = ctx.tex.set("Marble012");
            marble.setColor("BaseColor", new ColorRGBA(0.92f, 0.9f, 0.86f, 1f));
            mSkin = mTunic = mArmor = mTrim = mCrest = mIron = mLeather = mCloth = marble;
            r.mats.add(marble);
        } else {
            mSkin = ctx.tex.color(o.skin, 0f, 0.65f);
            mTunic = ctx.tex.color(o.tunic, 0f, 0.92f);
            mCloth = mTunic;
            mArmor = ctx.tex.color(new ColorRGBA(0.74f, 0.75f, 0.8f, 1f), 0.7f, 0.38f);
            mTrim = ctx.tex.color(new ColorRGBA(0.85f, 0.7f, 0.2f, 1f), 0.9f, 0.32f);
            mCrest = ctx.tex.color(o.crest, 0f, 0.85f);
            mIron = ctx.tex.color(new ColorRGBA(0.6f, 0.61f, 0.66f, 1f), 0.65f, 0.34f);
            mLeather = ctx.tex.color(new ColorRGBA(0.29f, 0.2f, 0.13f, 1f), 0f, 0.8f);
            r.mats.add(mSkin); r.mats.add(mTunic); r.mats.add(mArmor); r.mats.add(mTrim);
            r.mats.add(mCrest); r.mats.add(mIron); r.mats.add(mLeather);
        }

        r.root = new Node("rig");
        r.spinner = new Node("spinner");
        r.spinner.setLocalTranslation(0f, 0.9f, 0f);
        r.root.attachChild(r.spinner);
        r.inner = new Node("inner");
        r.inner.setLocalTranslation(0f, -0.9f, 0f);
        r.spinner.attachChild(r.inner);

        // ---- body (pelvis pivot) ----
        r.body = new Node("body");
        r.body.setLocalTranslation(0f, 0.95f, 0f);
        r.inner.attachChild(r.body);

        // segmented cuirass: chest + belly bands
        r.add(cylY(0.285f, 0.27f, 0.34f, 18, mArmor), r.body, 0f, 0.45f, 0f);
        r.add(cylY(0.27f, 0.275f, 0.1f, 18, mArmor), r.body, 0f, 0.24f, 0f);
        r.add(cylY(0.26f, 0.268f, 0.1f, 18, mArmor), r.body, 0f, 0.13f, 0f);
        // tunic skirt
        r.add(cylY(0.24f, 0.31f, 0.3f, 18, mTunic), r.body, 0f, -0.07f, 0f);
        // belt
        r.add(cylY(0.275f, 0.275f, 0.07f, 18, mLeather), r.body, 0f, 0.045f, 0f);

        // ---- head ----
        r.headG = new Node("head");
        r.headG.setLocalTranslation(0f, 0.68f, 0f);
        r.body.attachChild(r.headG);
        Geometry head = sphere(0.155f, mSkin);
        r.add(head, r.headG, 0f, 0.14f, 0f);
        if (!o.helmet.equals("none")) {
            Geometry dome = sphere(0.175f, mIron);
            dome.setLocalScale(1f, 0.92f, 1.05f);
            r.add(dome, r.headG, 0f, 0.18f, 0f);
            r.add(cylY(0.19f, 0.2f, 0.05f, 18, mIron), r.headG, 0f, 0.1f, -0.015f);
            // cheek guards
            r.add(geo(new Box(0.02f, 0.07f, 0.06f), mIron), r.headG, 0.15f, 0.04f, 0.05f);
            r.add(geo(new Box(0.02f, 0.07f, 0.06f), mIron), r.headG, -0.15f, 0.04f, 0.05f);
            if (o.helmet.equals("centurion")) {
                r.add(geo(new Box(0.035f, 0.085f, 0.21f), mCrest), r.headG, 0f, 0.4f, 0f);
                r.add(geo(new Box(0.02f, 0.04f, 0.16f), mTrim), r.headG, 0f, 0.32f, 0f);
            }
        }

        // ---- arms (shoulder pivots) ----
        r.rArm = r.makeArm(1, mArmor, mSkin);
        r.lArm = r.makeArm(-1, mArmor, mSkin);

        // ---- sword in the right hand ----
        r.swordG = new Node("sword");
        r.swordG.setLocalTranslation(0f, -0.58f, 0f);
        r.swordG.setLocalRotation(new com.jme3.math.Quaternion().fromAngles(FastMath.HALF_PI, 0f, 0f));
        r.add(cylY(0.022f, 0.022f, 0.13f, 8, mLeather), r.swordG, 0f, -0.04f, 0f);
        r.add(geo(new Box(0.08f, 0.018f, 0.025f), mTrim), r.swordG, 0f, 0.05f, 0f);
        r.add(geo(new Box(0.028f, o.swordLen / 2f, 0.008f), mIron), r.swordG, 0f, 0.05f + o.swordLen / 2f, 0f);
        r.rArm.attachChild(r.swordG);

        // ---- curved scutum on the left arm ----
        if (o.shield) {
            r.shieldG = new Node("shield");
            r.shieldG.setLocalTranslation(-0.06f, -0.4f, 0.16f);
            Geometry panel = new Geometry("scutum", curvedPanel(0.46f, 0.78f, 0.42f, 8));
            panel.setMaterial(mTunic);
            panel.getMaterial().getAdditionalRenderState().setFaceCullMode(RenderState.FaceCullMode.Off);
            panel.setShadowMode(RenderQueue.ShadowMode.CastAndReceive);
            r.meshes.add(panel);
            r.shieldG.attachChild(panel);
            Geometry bossKnob = sphere(0.07f, mTrim);
            bossKnob.setLocalScale(1f, 1f, 0.55f);
            r.add(bossKnob, r.shieldG, 0f, 0f, 0.065f);
            r.lArm.attachChild(r.shieldG);
        }

        // ---- legs (hip pivots) ----
        r.rLeg = r.makeLeg(1, mSkin, mLeather);
        r.lLeg = r.makeLeg(-1, mSkin, mLeather);

        r.root.setLocalScale(o.scale);
        return r;
    }

    private Node makeArm(int side, Material mArmor, Material mSkin) {
        Node g = new Node(side == 1 ? "rArm" : "lArm");
        g.setLocalTranslation(0.33f * side, 0.55f, 0f);
        Geometry pad = sphere(0.125f, mArmor);
        pad.setLocalScale(1.1f, 0.85f, 1.1f);
        add(pad, g, 0f, 0.01f, 0f);
        add(cylY(0.06f, 0.068f, 0.5f, 12, mSkin), g, 0f, -0.3f, 0f);
        add(sphere(0.07f, mSkin), g, 0f, -0.57f, 0f);
        body.attachChild(g);
        return g;
    }

    private Node makeLeg(int side, Material mSkin, Material mLeather) {
        Node g = new Node(side == 1 ? "rLeg" : "lLeg");
        g.setLocalTranslation(0.14f * side, 0.95f, 0f);
        add(cylY(0.07f, 0.08f, 0.84f, 12, mSkin), g, 0f, -0.48f, 0f);
        add(geo(new Box(0.08f, 0.035f, 0.13f), mLeather), g, 0f, -0.915f, 0.03f);
        inner.attachChild(g);
        return g;
    }

    // -------------------------------------------------------------- helpers

    private static Geometry geo(Mesh m, Material mat) {
        Geometry g = new Geometry("part", m);
        g.setMaterial(mat);
        g.setShadowMode(RenderQueue.ShadowMode.CastAndReceive);
        return g;
    }

    /** Upright (Y-axis) tapered cylinder. */
    private static Geometry cylY(float rTop, float rBottom, float h, int samples, Material mat) {
        Geometry g = geo(new Cylinder(2, samples, rTop, rBottom, h, true, false), mat);
        g.rotate(FastMath.HALF_PI, 0f, 0f);
        return g;
    }

    private static Geometry sphere(float r, Material mat) {
        return geo(new Sphere(14, 18, r), mat);
    }

    private void add(Geometry g, Node parent, float x, float y, float z) {
        g.move(x, y, z);
        parent.attachChild(g);
        meshes.add(g);
    }

    /** Curved shield panel: vertical cylinder section of the given arc width. */
    private static Mesh curvedPanel(float width, float height, float curveR, int segs) {
        float arc = width / curveR;
        int rows = 2;
        Vector3f[] pos = new Vector3f[(segs + 1) * (rows + 1)];
        Vector3f[] norm = new Vector3f[pos.length];
        Vector2f[] uv = new Vector2f[pos.length];
        int[] idx = new int[segs * rows * 6];
        for (int j = 0; j <= rows; j++) {
            float y = -height / 2f + height * j / rows;
            for (int i = 0; i <= segs; i++) {
                float a = -arc / 2f + arc * i / segs;
                int k = j * (segs + 1) + i;
                pos[k] = new Vector3f(FastMath.sin(a) * curveR, y, (FastMath.cos(a) - 1f) * curveR);
                norm[k] = new Vector3f(FastMath.sin(a), 0f, FastMath.cos(a));
                uv[k] = new Vector2f((float) i / segs, (float) j / rows);
            }
        }
        int n = 0;
        for (int j = 0; j < rows; j++) {
            for (int i = 0; i < segs; i++) {
                int a = j * (segs + 1) + i, b = a + 1, c = a + segs + 1, d = c + 1;
                idx[n++] = a; idx[n++] = c; idx[n++] = b;
                idx[n++] = b; idx[n++] = c; idx[n++] = d;
            }
        }
        Mesh m = new Mesh();
        m.setBuffer(VertexBuffer.Type.Position, 3, BufferUtils.createFloatBuffer(pos));
        m.setBuffer(VertexBuffer.Type.Normal, 3, BufferUtils.createFloatBuffer(norm));
        m.setBuffer(VertexBuffer.Type.TexCoord, 2, BufferUtils.createFloatBuffer(uv));
        m.setBuffer(VertexBuffer.Type.Index, 3, BufferUtils.createIntBuffer(idx));
        m.updateBound();
        return m;
    }

    /**
     * Corpse fade: 1 = opaque, towards 0 = transparent. Each rig owns its
     * materials, so mutating them is safe.
     */
    public void setFade(float alpha) {
        boolean transparent = alpha < 0.999f;
        if (transparent != faded) {
            faded = transparent;
            for (Geometry g : meshes) {
                g.setQueueBucket(transparent
                        ? RenderQueue.Bucket.Transparent : RenderQueue.Bucket.Opaque);
            }
            for (Material m : mats) {
                m.getAdditionalRenderState().setBlendMode(
                        transparent ? RenderState.BlendMode.Alpha : RenderState.BlendMode.Off);
            }
        }
        for (Material m : mats) {
            ColorRGBA c = (ColorRGBA) m.getParamValue("BaseColor");
            if (c != null) {
                m.setColor("BaseColor", new ColorRGBA(c.r, c.g, c.b, alpha));
            }
        }
    }
}
