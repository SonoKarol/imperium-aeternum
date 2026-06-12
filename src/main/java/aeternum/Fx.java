package aeternum;

import com.jme3.effect.ParticleEmitter;
import com.jme3.effect.ParticleMesh;
import com.jme3.effect.shapes.EmitterSphereShape;
import com.jme3.light.PointLight;
import com.jme3.material.Material;
import com.jme3.material.RenderState.BlendMode;
import com.jme3.math.ColorRGBA;
import com.jme3.math.FastMath;
import com.jme3.math.Vector3f;
import com.jme3.renderer.queue.RenderQueue;
import com.jme3.scene.Geometry;
import com.jme3.scene.Node;
import com.jme3.scene.shape.Torus;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Visual effects subsystem. Port of {@code web/src/fx.js} plus the persistent
 * fire recipe (flame, embers, flickering point light) from
 * {@code web/src/world.js}.
 *
 * <p>One-shot bursts (blood, dust, sparks, souls) are {@link ParticleEmitter}s
 * with {@code particlesPerSec = 0} that emit all their particles once and are
 * detached after the particle lifetime expires. The boss slam shockwave is an
 * expanding flattened torus with a fading glow material. Fires registered via
 * {@link #attachFire} are flickered every frame in {@link #update} with the
 * sin-product recipe from world.js.</p>
 */
public class Fx {

    // Burst palette, ported 1:1 from fx.js (0x8b1212, 0xffd060, 0x9a8a6c, 0xc9a227).
    private static final ColorRGBA BLOOD_C = new ColorRGBA(0.545f, 0.071f, 0.071f, 1f);
    private static final ColorRGBA SPARK_C = new ColorRGBA(1.000f, 0.816f, 0.376f, 1f);
    private static final ColorRGBA DUST_C  = new ColorRGBA(0.604f, 0.541f, 0.424f, 1f);
    private static final ColorRGBA SOUL_C  = new ColorRGBA(0.788f, 0.635f, 0.153f, 1f);
    // Shockwave ring color (0xffaa44) and fire light colors (0xffa040 / 0xff7a2a).
    private static final ColorRGBA RING_C       = new ColorRGBA(1f, 0.667f, 0.267f, 1f);
    private static final ColorRGBA FIRE_LARGE_C = new ColorRGBA(1f, 0.627f, 0.251f, 1f);
    private static final ColorRGBA FIRE_SMALL_C = new ColorRGBA(1f, 0.478f, 0.165f, 1f);

    /** Every fire light created by {@link #attachFire}, exposed for World. */
    public final List<PointLight> fires = new ArrayList<>();

    private final GameCtx ctx;
    private final Material matAdditive;  // glowy particles (sparks, souls, flames)
    private final Material matAlpha;     // matter particles (blood, dust)

    private final List<Burst> bursts = new ArrayList<>();
    private final List<Ring> rings = new ArrayList<>();
    private final List<Fire> firePairs = new ArrayList<>();

    private final ColorRGBA cTmp = new ColorRGBA();
    private final Vector3f vTmp = new Vector3f();

    /** One-shot burst awaiting cleanup. */
    private static final class Burst {
        final ParticleEmitter emitter;
        float life;

        Burst(ParticleEmitter emitter, float life) {
            this.emitter = emitter;
            this.life = life;
        }
    }

    /** Expanding shockwave ring. */
    private static final class Ring {
        final Node node;
        final Material mat;
        final float maxR, dur;
        float t;

        Ring(Node node, Material mat, float maxR, float dur) {
            this.node = node;
            this.mat = mat;
            this.maxR = maxR;
            this.dur = dur;
        }
    }

    /** Flickering fire light: {light, baseIntensity} pair plus its anchor. */
    private static final class Fire {
        final PointLight light;
        final Node parent;
        final ColorRGBA color;
        final float base, yOff;

        Fire(PointLight light, Node parent, ColorRGBA color, float base, float yOff) {
            this.light = light;
            this.parent = parent;
            this.color = color;
            this.base = base;
            this.yOff = yOff;
        }
    }

    public Fx(GameCtx ctx) {
        this.ctx = ctx;
        matAdditive = particleMaterial();
        matAlpha = particleMaterial();
        matAlpha.getAdditionalRenderState().setBlendMode(BlendMode.Alpha);
    }

    private com.jme3.texture.Texture2D softDot;

    private Material particleMaterial() {
        if (softDot == null) softDot = makeSoftDot();
        Material m = new Material(ctx.assets, "Common/MatDefs/Misc/Particle.j3md");
        m.setTexture("Texture", softDot);
        return m;
    }

    /** Radial-gradient sprite generated in memory — no asset files needed. */
    private static com.jme3.texture.Texture2D makeSoftDot() {
        int s = 64;
        java.nio.ByteBuffer data = com.jme3.util.BufferUtils.createByteBuffer(s * s * 4);
        for (int y = 0; y < s; y++) {
            for (int x = 0; x < s; x++) {
                float dx = (x - s / 2f) / (s / 2f), dy = (y - s / 2f) / (s / 2f);
                float d = FastMath.sqrt(dx * dx + dy * dy);
                float a = FastMath.clamp(1f - d, 0f, 1f);
                a *= a;
                data.put((byte) 255).put((byte) 255).put((byte) 255).put((byte) (a * 255));
            }
        }
        data.flip();
        com.jme3.texture.Image img = new com.jme3.texture.Image(
                com.jme3.texture.Image.Format.RGBA8, s, s, data,
                com.jme3.texture.image.ColorSpace.Linear);
        return new com.jme3.texture.Texture2D(img);
    }

    // ------------------------------------------------------------- bursts

    /** Dark red hit splatter (fx.js: count 16, speed 3.5, size .08, life .55, up 2.5). */
    public void blood(Vector3f pos) {
        burst(pos, BLOOD_C, 16, 3.5f, 0.08f, 0.55f, 2.5f, 9f, false);
    }

    /** Tan ground dust puff (fx.js: count 20, speed 5, size .12, life .7, up 1.2). */
    public void dust(Vector3f pos) {
        burst(pos, DUST_C, 20, 5f, 0.12f, 0.7f, 1.2f, 9f, false);
    }

    /** Bright yellow weapon-clang sparks (fx.js: count 10, speed 5, size .06, life .3, up 1.5). */
    public void spark(Vector3f pos) {
        burst(pos, SPARK_C, 10, 5f, 0.06f, 0.3f, 1.5f, 9f, true);
    }

    /** Golden rising soul burst (fx.js: count 24, speed 1.5, size .1, life 1.1, up 4). */
    public void soul(Vector3f pos) {
        // Upward drift instead of fx.js's gravity-plus-floor-clamp (per-particle
        // ground clamping is not expressible with the stock influencer).
        burst(pos, SOUL_C, 24, 1.5f, 0.1f, 1.1f, 4f, -2f, true);
    }

    private void burst(Vector3f pos, ColorRGBA color, int count, float speed, float size,
                       float life, float up, float gravity, boolean additive) {
        ParticleEmitter em = new ParticleEmitter("fx-burst", ParticleMesh.Type.Triangle, count);
        em.setMaterial(additive ? matAdditive : matAlpha);
        em.setImagesX(2);
        em.setImagesY(2);
        em.setSelectRandomImage(true);
        em.setStartColor(new ColorRGBA(color.r, color.g, color.b, 1f));
        em.setEndColor(new ColorRGBA(color.r, color.g, color.b, 0f));
        em.setStartSize(size * 2f);
        em.setEndSize(size);
        em.setLowLife(life * 0.75f);
        em.setHighLife(life);
        em.setGravity(0f, gravity, 0f);
        // fx.js scatters speed*(0.4..1) horizontally and up*(0.3..1) vertically;
        // approximated with the mean vector plus a high velocity variation.
        em.getParticleInfluencer().setInitialVelocity(new Vector3f(speed * 0.7f, up * 0.65f, 0f));
        em.getParticleInfluencer().setVelocityVariation(0.8f);
        em.setParticlesPerSec(0f);
        em.setRandomAngle(true);
        em.setInWorldSpace(false); // particles are placed relative to the emitter
        em.setQueueBucket(RenderQueue.Bucket.Transparent);
        em.setShadowMode(RenderQueue.ShadowMode.Off);
        em.setLocalTranslation(pos);
        ctx.rootNode.attachChild(em);
        em.emitAllParticles();
        bursts.add(new Burst(em, life + 0.15f));
    }

    // ---------------------------------------------------------- shockwave

    /**
     * Expanding flattened ring for the boss slam. Scale grows from 1 to
     * {@code 1 + maxR} over {@code dur} seconds while the alpha fades from 0.9
     * to 0; the ring is removed when done (port of fx.js shockwave).
     */
    public void shockwave(Vector3f pos, float maxR, float dur) {
        // fx.js ring band 0.8..1.0 -> torus of major radius 0.9, thin tube.
        Geometry g = new Geometry("fx-shockwave", new Torus(40, 12, 0.09f, 0.9f));
        Material m = ctx.tex.glow(new ColorRGBA(RING_C.r, RING_C.g, RING_C.b, 0.9f), 2f);
        m.getAdditionalRenderState().setBlendMode(BlendMode.Alpha);
        m.getAdditionalRenderState().setDepthWrite(false);
        g.setMaterial(m);
        g.rotate(FastMath.HALF_PI, 0f, 0f); // jME torus lies in X-Y: lay it flat
        g.setQueueBucket(RenderQueue.Bucket.Transparent);
        g.setShadowMode(RenderQueue.ShadowMode.Off);
        Node n = new Node("fx-shockwave");
        n.attachChild(g);
        n.setLocalTranslation(pos.x, pos.y + 0.15f, pos.z);
        ctx.rootNode.attachChild(n);
        rings.add(new Ring(n, m, maxR, dur));
    }

    // -------------------------------------------------------------- fires

    /**
     * Attaches a persistent fire (flame emitter, ember sparks) to {@code parent}
     * and registers a point light flickered every frame by {@link #update}.
     * The light itself is added to the root node and follows the parent.
     *
     * @param large shrine-sized fire when true, brazier-sized when false
     * @return the flickering point light (also appended to {@link #fires})
     */
    public PointLight attachFire(Node parent, boolean large) {
        float flameY = large ? 1.8f : 1.65f;

        ParticleEmitter flame = new ParticleEmitter("fx-flame", ParticleMesh.Type.Triangle, large ? 40 : 24);
        flame.setMaterial(matAdditive);
        flame.setImagesX(2);
        flame.setImagesY(2);
        flame.setSelectRandomImage(true);
        flame.setStartColor(new ColorRGBA(1f, 0.55f, 0.18f, 0.85f));   // orange
        flame.setEndColor(new ColorRGBA(0.45f, 0.05f, 0.03f, 0f));     // dark red
        flame.setStartSize(large ? 0.55f : 0.32f);
        flame.setEndSize(large ? 0.18f : 0.10f);
        flame.setLowLife(large ? 0.7f : 0.5f);
        flame.setHighLife(large ? 1.2f : 0.9f);
        flame.setGravity(0f, -0.3f, 0f);                               // rises
        flame.getParticleInfluencer().setInitialVelocity(new Vector3f(0f, large ? 1.6f : 1.1f, 0f));
        flame.getParticleInfluencer().setVelocityVariation(0.3f);
        flame.setParticlesPerSec(large ? 22f : 14f);
        flame.setShape(new EmitterSphereShape(Vector3f.ZERO, large ? 0.3f : 0.18f));
        flame.setRandomAngle(true);
        flame.setInWorldSpace(false);
        flame.setQueueBucket(RenderQueue.Bucket.Transparent);
        flame.setShadowMode(RenderQueue.ShadowMode.Off);
        flame.setLocalTranslation(0f, flameY, 0f);
        parent.attachChild(flame);

        ParticleEmitter embers = new ParticleEmitter("fx-embers", ParticleMesh.Type.Triangle, large ? 28 : 16);
        embers.setMaterial(matAdditive);
        embers.setImagesX(2);
        embers.setImagesY(2);
        embers.setSelectRandomImage(true);
        embers.setStartColor(new ColorRGBA(1f, 0.75f, 0.38f, 0.9f));   // 0xffc060
        embers.setEndColor(new ColorRGBA(1f, 0.40f, 0.12f, 0f));
        embers.setStartSize(large ? 0.10f : 0.07f);
        embers.setEndSize(large ? 0.03f : 0.02f);
        embers.setLowLife(large ? 1.6f : 1.4f);
        embers.setHighLife(large ? 2.6f : 2.2f);
        embers.setGravity(0f, -0.4f, 0f);                              // accelerates upward
        embers.getParticleInfluencer().setInitialVelocity(new Vector3f(0f, large ? 1.2f : 0.9f, 0f));
        embers.getParticleInfluencer().setVelocityVariation(0.6f);
        embers.setParticlesPerSec(large ? 9f : 5f);
        embers.setShape(new EmitterSphereShape(Vector3f.ZERO, large ? 0.25f : 0.15f));
        embers.setInWorldSpace(false);
        embers.setQueueBucket(RenderQueue.Bucket.Transparent);
        embers.setShadowMode(RenderQueue.ShadowMode.Off);
        embers.setLocalTranslation(0f, flameY, 0f);
        parent.attachChild(embers);

        ColorRGBA color = large ? FIRE_LARGE_C : FIRE_SMALL_C;
        float base = large ? 5.5f : 3.0f;
        float yOff = large ? 2.4f : 2.0f;
        PointLight light = new PointLight();
        light.setColor(color.mult(base));
        light.setRadius(large ? 22f : 15f);
        light.setPosition(parent.getWorldTranslation().add(0f, yOff, 0f));
        ctx.rootNode.addLight(light);

        firePairs.add(new Fire(light, parent, color, base, yOff));
        fires.add(light);
        return light;
    }

    // ------------------------------------------------------------- update

    /** Advances burst/ring lifetimes and flickers every registered fire light. */
    public void update(float tpf) {
        // bursts: detach once the longest particle life has expired
        for (Iterator<Burst> it = bursts.iterator(); it.hasNext(); ) {
            Burst b = it.next();
            b.life -= tpf;
            if (b.life <= 0f) {
                b.emitter.removeFromParent();
                it.remove();
            }
        }

        // shockwave rings: expand, fade, remove at end (fx.js curve)
        for (Iterator<Ring> it = rings.iterator(); it.hasNext(); ) {
            Ring r = it.next();
            r.t += tpf;
            float k = Math.min(1f, r.t / r.dur);
            float s = 1f + k * r.maxR;
            r.node.setLocalScale(s, 1f, s);
            float a = 0.9f * (1f - k);
            r.mat.setColor("BaseColor", new ColorRGBA(RING_C.r, RING_C.g, RING_C.b, a));
            r.mat.setColor("Emissive", new ColorRGBA(RING_C.r * 2f * a, RING_C.g * 2f * a, RING_C.b * 2f * a, 1f));
            if (k >= 1f) {
                r.node.removeFromParent();
                it.remove();
            }
        }

        // fire flicker: world.js -> base * (0.85 + 0.3*sin(t*11+base)*sin(t*17))
        float time = ctx.time;
        for (Fire f : firePairs) {
            float i = f.base * (0.85f + 0.3f * FastMath.sin(time * 11f + f.base) * FastMath.sin(time * 17f));
            cTmp.set(f.color.r * i, f.color.g * i, f.color.b * i, 1f);
            f.light.setColor(cTmp);
            f.light.setPosition(vTmp.set(f.parent.getWorldTranslation()).addLocal(0f, f.yOff, 0f));
        }
    }
}
