package aeternum;

import com.jme3.material.Material;
import com.jme3.material.RenderState;
import com.jme3.math.ColorRGBA;
import com.jme3.math.FastMath;
import com.jme3.math.Quaternion;
import com.jme3.renderer.queue.RenderQueue;
import com.jme3.scene.Geometry;
import com.jme3.scene.Node;
import com.jme3.scene.Spatial;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Character rig: a Blender-sculpted legionary (Models/legionary.glb, see
 * docs/CHARACTER-SPEC.md) whose named parts are re-parented onto animation
 * pivot nodes. Hierarchy: root -> spinner(y=.9) -> inner(y=-.9) -> body/limbs;
 * `spinner` pivots at hip height so dodge-rolls read as somersaults while
 * `inner` cancels the offset so pose code works in feet-space.
 */
public class Rig {

    public Node root, spinner, inner, body, headG, lArm, rArm, lLeg, rLeg, swordG, shieldG;
    public final List<Geometry> meshes = new ArrayList<>();
    public float scale;

    /** Per-character look options. */
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

    private static Spatial template;

    // this rig's materials (cloned from the template) so setFade is per-character
    private final Set<Material> mats = new LinkedHashSet<>();
    private boolean faded;

    private Rig() {}

    public static Rig humanoid(GameCtx ctx, Options o) {
        if (template == null) {
            template = ctx.assets.loadModel("Models/legionary.glb");
        }
        Node model = (Node) template.clone(); // shares meshes, clones materials

        Rig r = new Rig();
        r.scale = o.scale;

        r.root = new Node("rig");
        r.spinner = new Node("spinner");
        r.spinner.setLocalTranslation(0f, 0.9f, 0f);
        r.root.attachChild(r.spinner);
        r.inner = new Node("inner");
        r.inner.setLocalTranslation(0f, -0.9f, 0f);
        r.spinner.attachChild(r.inner);

        r.body = new Node("body");
        r.body.setLocalTranslation(0f, 0.95f, 0f);
        r.inner.attachChild(r.body);

        r.headG = new Node("head");
        r.headG.setLocalTranslation(0f, 0.68f, 0f);
        r.body.attachChild(r.headG);

        r.rArm = pivot("rArm", r.body, 0.33f, 0.55f, 0f);
        r.lArm = pivot("lArm", r.body, -0.33f, 0.55f, 0f);
        r.rLeg = pivot("rLeg", r.inner, 0.14f, 0.95f, 0f);
        r.lLeg = pivot("lLeg", r.inner, -0.14f, 0.95f, 0f);

        // ---- mount the sculpted parts on their pivots ----
        r.mount(model, "Torso", r.body);
        r.mount(model, "Head", r.headG);
        if (!o.helmet.equals("none")) {
            r.mount(model, "Helmet", r.headG);
            if (o.helmet.equals("centurion")) {
                r.mount(model, "Crest", r.headG);
            }
        }
        r.mount(model, "ArmR", r.rArm);
        r.mount(model, "ArmL", r.lArm);
        r.mount(model, "LegR", r.rLeg);
        r.mount(model, "LegL", r.lLeg);

        // sword: grip pivot inside the right hand, blade modeled +Y;
        // the resting rotation points it forward like the old rig did
        r.swordG = new Node("sword");
        r.swordG.setLocalTranslation(0.03f, -0.57f, 0.05f);
        r.swordG.setLocalRotation(new Quaternion().fromAngles(FastMath.HALF_PI, 0f, 0f));
        r.rArm.attachChild(r.swordG);
        Spatial sword = r.mount(model, "Sword", r.swordG);
        if (sword != null && o.swordLen != 0.8f) {
            sword.setLocalScale(1f, o.swordLen / 0.8f, 1f);
        }

        if (o.shield) {
            r.shieldG = new Node("shield");
            r.shieldG.setLocalTranslation(-0.06f, -0.4f, 0.16f);
            r.lArm.attachChild(r.shieldG);
            r.mount(model, "Shield", r.shieldG);
        }

        // ---- collect geometry, apply shadows and per-variant tinting ----
        Material marble = o.marble ? ctx.tex.set("Marble012") : null;
        if (marble != null) {
            marble.setColor("BaseColor", new ColorRGBA(0.92f, 0.9f, 0.86f, 1f));
        }
        r.root.depthFirstTraversal(s -> {
            if (s instanceof Geometry g) {
                g.setShadowMode(RenderQueue.ShadowMode.CastAndReceive);
                r.meshes.add(g);
                if (marble != null) {
                    g.setMaterial(marble);
                } else {
                    tint(g.getMaterial(), o);
                }
                r.mats.add(g.getMaterial());
            }
        });

        r.root.setLocalScale(o.scale);
        return r;
    }

    private static Node pivot(String name, Node parent, float x, float y, float z) {
        Node n = new Node(name);
        n.setLocalTranslation(x, y, z);
        parent.attachChild(n);
        return n;
    }

    /** Detach the named part from the loaded model and hang it on a pivot. */
    private Spatial mount(Node model, String name, Node pivotNode) {
        Spatial part = model.getChild(name);
        if (part == null) {
            throw new IllegalStateException("legionary.glb is missing part: " + name);
        }
        part.removeFromParent();
        part.setLocalTranslation(0f, 0f, 0f);
        pivotNode.attachChild(part);
        return part;
    }

    private static void tint(Material m, Options o) {
        String name = m.getName() == null ? "" : m.getName();
        if (name.startsWith("Tunic")) m.setColor("BaseColor", o.tunic.clone());
        else if (name.startsWith("Skin")) m.setColor("BaseColor", o.skin.clone());
        else if (name.startsWith("Crest")) m.setColor("BaseColor", o.crest.clone());
    }

    /**
     * Corpse fade: 1 = opaque, towards 0 = transparent. Materials are cloned
     * per character, so mutating them is safe.
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
            Object v = m.getParamValue("BaseColor");
            if (v instanceof ColorRGBA c) {
                m.setColor("BaseColor", new ColorRGBA(c.r, c.g, c.b, alpha));
            }
        }
    }
}
