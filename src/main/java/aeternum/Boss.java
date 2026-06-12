package aeternum;

import com.jme3.light.PointLight;
import com.jme3.material.Material;
import com.jme3.math.ColorRGBA;
import com.jme3.math.FastMath;
import com.jme3.math.Quaternion;
import com.jme3.math.Vector3f;
import com.jme3.renderer.queue.RenderQueue;
import com.jme3.scene.Geometry;
import com.jme3.scene.Spatial;
import com.jme3.scene.shape.Box;

/**
 * CENTVRIO INVICTVS — the colosseum boss. Port of {@code web/src/boss.js}.
 * Two phases:
 * <ul>
 *   <li>&gt;50% hp: 2–3 step combos and a punishable charge;</li>
 *   <li>&lt;50% hp: enrage — faster, ember aura, adds a leaping slam with an
 *       expanding shockwave ring.</li>
 * </ul>
 * States: dormant|shout|chase|combo|charge|slam|stagger|dead|gone. Defeat is
 * persistent across rests ({@link #defeated}).
 */
public class Boss implements Combatant {

    public static final String NAME = "CENTVRIO INVICTVS, CVSTOS AETERNVS";

    public float hp, maxHp = 950;
    public int gloria = 1800;
    public boolean defeated, justDied, enraged;
    /** Current AI state: dormant|shout|chase|combo|charge|slam|stagger|dead|gone. */
    public String state;

    private static final int DMG_COMBO = 30;
    private static final int DMG_CHARGE = 46;
    private static final int DMG_SLAM = 52;
    private static final ColorRGBA AURA_COLOR = new ColorRGBA(1f, 0.133f, 0f, 1f);
    /** Maps the JS PointLight intensity (0..10) to a jME light color multiplier. */
    private static final float AURA_GAIN = 0.5f;

    private final GameCtx ctx;
    private final Rig rig;
    private final PointLight aura;
    private final Vector3f home;
    private final Vector3f position = new Vector3f();
    private final Quaternion tmpRot = new Quaternion();

    private float auraIntensity;
    private float time;
    private float stateT;
    private float yaw;
    private float poiseAcc;
    private float cd;
    private float walkCycle;
    private int comboStep;
    private boolean didHit;
    private Vector3f slamTarget;
    private boolean shockActive;
    private float shockT;

    /** Builds the rig (ember eyes, enrage aura) and kneels dormant in the arena. */
    public Boss(GameCtx ctx) {
        this.ctx = ctx;

        Rig.Options o = new Rig.Options();
        o.scale = 1.85f;
        o.tunic = rgb(0x401010);
        o.skin = rgb(0x7a5a44);
        o.crest = rgb(0xd01818);
        o.helmet = "centurion";
        o.shield = false;
        o.swordLen = 1.25f;
        this.rig = Rig.humanoid(ctx, o);
        ctx.rootNode.attachChild(rig.root);

        // ember eyes
        Material eyeMat = ctx.tex.glow(new ColorRGBA(1f, 0.2f, 0f, 1f), 4f);
        for (float sx : new float[]{-0.07f, 0.07f}) {
            Geometry eye = new Geometry("bossEye", new Box(0.025f, 0.015f, 0.01f));
            eye.setMaterial(eyeMat);
            eye.setLocalTranslation(sx, 0.16f, 0.14f);
            eye.setShadowMode(RenderQueue.ShadowMode.Off);
            rig.headG.attachChild(eye);
        }

        // enrage aura — followed manually each frame (lights don't parent to nodes)
        this.aura = new PointLight();
        this.aura.setRadius(14f);
        this.aura.setColor(ColorRGBA.Black);
        ctx.rootNode.addLight(aura);

        this.home = new Vector3f(C.ARENA_X, 0, C.ARENA_Z - 6);
        reset();
    }

    /** Back to the dormant kneel at full health (no-op visually once defeated). */
    public void reset() {
        position.set(home);
        position.y = ctx.world.getHeight(position.x, position.z);
        hp = maxHp;
        state = "dormant";
        stateT = 0;
        yaw = 0;
        poiseAcc = 0;
        cd = 0;
        enraged = false;
        justDied = false;
        comboStep = 0;
        didHit = false;
        slamTarget = null;
        shockActive = false;
        auraIntensity = 0;
        rig.setFade(1f);
        rig.root.setCullHint(defeated ? Spatial.CullHint.Always : Spatial.CullHint.Inherit);
        rig.root.setLocalTranslation(position);
        rig.root.setLocalRotation(tmpRot.fromAngles(0, yaw, 0));
        updateAura();
    }

    /** Triggers the awakening roar when the player steps into the arena. */
    public void awaken(Vector3f playerPos) {
        if (!isAlive() || !"dormant".equals(state)) return;
        state = "shout";
        stateT = 0;
        faceInstant(playerPos);
        ctx.audio.bossRoar();
    }

    /** Alive and no longer dormant — i.e. the fight is on. */
    public boolean isActive() {
        return isAlive() && !"dormant".equals(state);
    }

    // ------------------------------------------------------------- Combatant
    @Override
    public Vector3f pos() {
        return position;
    }

    @Override
    public boolean isAlive() {
        return !defeated && !"dead".equals(state) && !"gone".equals(state);
    }

    @Override
    public float radius() {
        return 1.0f;
    }

    @Override
    public void takeDamage(int amount, int poiseDmg, Vector3f from) {
        if (!isAlive() || "dormant".equals(state)) return;
        hp -= amount;
        poiseAcc += poiseDmg;
        if (hp <= 0) {
            hp = 0;
            state = "dead";
            stateT = 0;
            justDied = true;
            defeated = true;
            ctx.audio.bossRoar();
            ctx.fx.soul(new Vector3f(position.x, position.y + 2f, position.z));
            return;
        }
        if (!enraged && hp < maxHp * 0.5f) {
            enraged = true;
            state = "shout";
            stateT = 0;
            ctx.audio.bossRoar();
            auraIntensity = 9;
        }
        // very high poise: only staggers after sustained pressure
        if (poiseAcc >= 220) {
            poiseAcc = 0;
            state = "stagger";
            stateT = 0;
        }
    }

    // ---------------------------------------------------------------- update
    /** Per-frame boss AI tick; the player is read from the context. */
    public void update(float tpf) {
        time += tpf;
        stateT += tpf;
        cd -= tpf;
        if (defeated && "gone".equals(state)) return;

        PlayerCtrl player = ctx.player;
        float speed = enraged ? 4.9f : 3.9f;
        float tox = player.position.x - position.x;
        float toz = player.position.z - position.z;
        float dist = FastMath.sqrt(tox * tox + toz * toz);
        float moving = 0;

        switch (state) {
            case "dormant":
                break;
            case "shout":
                if (stateT > 1.4f) {
                    state = "chase";
                    stateT = 0;
                    cd = 0.6f;
                }
                break;
            case "chase": {
                if (!player.isAlive()) break;
                faceToward(player.position, tpf, 6);
                if (cd > 0) {
                    // circle slowly while on cooldown
                    if (dist < 5) {
                        float k = speed * 0.3f * tpf;
                        position.x += FastMath.cos(yaw) * k;
                        position.z += -FastMath.sin(yaw) * k;
                        moving = 0.35f;
                    } else {
                        stepForward(speed * 0.7f, tpf);
                        moving = 0.7f;
                    }
                    break;
                }
                // pick an attack
                if (enraged && dist > 5 && dist < 14 && FastMath.nextRandomFloat() < 0.4f) {
                    state = "slam";
                    stateT = 0;
                    slamTarget = player.position.clone();
                    didHit = false;
                } else if (dist > 7.5f) {
                    state = "charge";
                    stateT = 0;
                    didHit = false;
                    ctx.audio.swingHeavy();
                } else if (dist < 4.5f) {
                    state = "combo";
                    stateT = 0;
                    comboStep = 0;
                    didHit = false;
                    ctx.audio.swing();
                } else {
                    stepForward(speed, tpf);
                    moving = 1;
                }
                break;
            }
            case "combo": {
                float stepDur = enraged ? 0.75f : 0.9f;
                float ph = stateT / stepDur;
                if (ph < 0.3f) faceToward(player.position, tpf, 5);
                if (ph > 0.36f && ph < 0.56f) {
                    stepForward(3.2f, tpf);
                    // keep checking through the window until the swing connects
                    if (!didHit && tryHitPlayer(3.4f, DMG_COMBO, 1.1f)) didHit = true;
                }
                if (ph >= 1) {
                    comboStep++;
                    didHit = false;
                    stateT = 0;
                    int maxSteps = enraged ? 3 : 2;
                    if (comboStep > maxSteps || !player.isAlive()) {
                        state = "chase";
                        cd = enraged ? 0.8f : 1.5f;
                    } else {
                        ctx.audio.swing();
                    }
                }
                break;
            }
            case "charge": {
                float ph = stateT;
                if (ph < 0.7f) { // windup, track player
                    faceToward(player.position, tpf, 4);
                } else if (ph < 1.6f) { // dash
                    stepForward(enraged ? 16f : 13.5f, tpf);
                    moving = 1;
                    if (!didHit && tryHitPlayer(2.6f, DMG_CHARGE, 0.8f)) didHit = true;
                    // stop at the arena wall
                    float dHome = FastMath.sqrt(
                            (position.x - C.ARENA_X) * (position.x - C.ARENA_X)
                            + (position.z - C.ARENA_Z) * (position.z - C.ARENA_Z));
                    if (dHome > C.ARENA_R_IN - 2) {
                        stateT = Math.max(stateT, 1.6f);
                        ctx.fx.dust(position.clone());
                    }
                } else if (ph > 2.6f) { // long recovery — the punish window
                    state = "chase";
                    stateT = 0;
                    cd = enraged ? 0.7f : 1.3f;
                }
                break;
            }
            case "slam": {
                final float DUR = 1.5f;
                float ph = stateT / DUR;
                if (ph < 0.45f) {
                    // leap toward the marked position
                    float k = Math.min(1f, ph / 0.45f);
                    position.x += (slamTarget.x - position.x) * k * tpf * 6f;
                    position.z += (slamTarget.z - position.z) * k * tpf * 6f;
                    faceToward(slamTarget, tpf, 8);
                } else if (!didHit && ph >= 0.5f) {
                    didHit = true;
                    ctx.fx.shockwave(position.clone(), 8f, 0.55f);
                    ctx.fx.dust(position.clone());
                    ctx.audio.hit();
                    shockActive = true;
                    shockT = 0;
                }
                if (didHit && shockActive) {
                    // timed expanding ring: hurts the player crossing its radius
                    shockT += tpf;
                    float r = 1 + (shockT / 0.55f) * 8f;
                    float pdx = player.position.x - position.x;
                    float pdz = player.position.z - position.z;
                    float d = FastMath.sqrt(pdx * pdx + pdz * pdz);
                    if (Math.abs(d - r) < 1.1f && shockT < 0.55f) {
                        if (hitPlayer(DMG_SLAM)) shockActive = false;
                    }
                }
                if (ph >= 1) {
                    state = "chase";
                    stateT = 0;
                    cd = 1.1f;
                    shockActive = false;
                }
                break;
            }
            case "stagger":
                if (stateT > 1.3f) {
                    state = "chase";
                    stateT = 0;
                    cd = 0.5f;
                }
                break;
            case "dead":
                if (stateT > 5) {
                    float k = Math.min(1f, (stateT - 5) / 2f);
                    rig.setFade(1 - k);
                    auraIntensity = 0;
                    if (k >= 1) {
                        state = "gone";
                        rig.root.setCullHint(Spatial.CullHint.Always);
                    }
                }
                break;
            default:
                break;
        }

        if (isAlive() && !"dormant".equals(state)) {
            ctx.world.resolveStatics(position, radius(), ctx.game.bossFight);
            // tether inside the arena
            float dHome = FastMath.sqrt(
                    (position.x - C.ARENA_X) * (position.x - C.ARENA_X)
                    + (position.z - C.ARENA_Z) * (position.z - C.ARENA_Z));
            if (dHome > C.ARENA_R_IN - 1.2f) {
                float k = (C.ARENA_R_IN - 1.2f) / dHome;
                position.x = C.ARENA_X + (position.x - C.ARENA_X) * k;
                position.z = C.ARENA_Z + (position.z - C.ARENA_Z) * k;
            }
            position.y = ctx.world.getHeight(position.x, position.z);
        }

        if (enraged && isAlive()) {
            auraIntensity = 7 + 3 * FastMath.sin(time * 9);
        }
        updateAura();

        if (moving > 0) walkCycle += tpf * speed * 1.5f;
        updateRig(moving);
    }

    // --------------------------------------------------------------- helpers
    private void faceInstant(Vector3f p) {
        yaw = FastMath.atan2(p.x - position.x, p.z - position.z);
    }

    private void faceToward(Vector3f p, float dt, float rate) {
        float t = FastMath.atan2(p.x - position.x, p.z - position.z);
        yaw += C.angleDiff(t, yaw) * Math.min(1f, dt * rate);
    }

    private void stepForward(float speed, float dt) {
        position.x += FastMath.sin(yaw) * speed * dt;
        position.z += FastMath.cos(yaw) * speed * dt;
    }

    /** Range + arc check against the player; true if the damage landed. */
    private boolean tryHitPlayer(float reach, int dmg, float arc) {
        PlayerCtrl p = ctx.player;
        if (!p.isAlive()) return false;
        float dx = p.position.x - position.x;
        float dz = p.position.z - position.z;
        float d = FastMath.sqrt(dx * dx + dz * dz);
        if (d > reach + p.radius()) return false;
        float ang = Math.abs(C.angleDiff(FastMath.atan2(dx, dz), yaw));
        if (ang > arc) return false;
        return hitPlayer(dmg);
    }

    /** Applies damage to the player; true if it landed (not i-framed/resting). */
    private boolean hitPlayer(int dmg) {
        if (!ctx.player.isAlive()) return false;
        float before = ctx.player.hp;
        ctx.player.takeDamage(dmg, 0, position.clone());
        return ctx.player.hp < before;
    }

    private void updateAura() {
        aura.setPosition(new Vector3f(position.x, position.y + 2.4f * rig.scale, position.z));
        aura.setColor(AURA_COLOR.mult(Math.max(0f, auraIntensity) * AURA_GAIN));
    }

    private void updateRig(float moving) {
        Poses.reset(rig);
        switch (state) {
            case "shout": {
                float k = FastMath.sin(FastMath.PI * Math.min(1f, stateT / 1.4f));
                Poses.shout(rig, k);
                break;
            }
            case "combo": {
                float stepDur = enraged ? 0.75f : 0.9f;
                Poses.bossCombo(rig, Math.min(1f, stateT / stepDur), comboStep % 3);
                break;
            }
            case "charge":
                Poses.bossCharge(rig, Math.min(1f, stateT / 2.6f));
                break;
            case "slam":
                Poses.bossSlam(rig, Math.min(1f, stateT / 1.5f));
                break;
            case "stagger":
                Poses.stagger(rig, stateT / 1.3f);
                break;
            case "dead":
            case "gone":
                Poses.death(rig, Math.min(1f, stateT / 1.6f));
                break;
            case "dormant":
                // kneeling, waiting through the ages
                Poses.rest(rig);
                break;
            default:
                if (moving > 0) Poses.walk(rig, walkCycle, Math.min(1f, moving));
                else Poses.idle(rig, time);
        }
        rig.root.setLocalTranslation(position);
        rig.root.setLocalRotation(tmpRot.fromAngles(0, yaw, 0));
    }

    private static ColorRGBA rgb(int hex) {
        return new ColorRGBA(((hex >> 16) & 0xff) / 255f,
                ((hex >> 8) & 0xff) / 255f, (hex & 0xff) / 255f, 1f);
    }
}
