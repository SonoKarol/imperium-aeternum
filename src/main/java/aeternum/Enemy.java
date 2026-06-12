package aeternum;

import com.jme3.math.ColorRGBA;
import com.jme3.math.FastMath;
import com.jme3.math.Quaternion;
import com.jme3.math.Vector3f;
import com.jme3.scene.Spatial;

/**
 * A corrupted legionary patrolling the ruins. Port of {@code web/src/enemies.js}.
 * AI states: idle -&gt; chase -&gt; strafe/attack -&gt; (stagger) -&gt; dead -&gt; gone.
 * Corpses fade away a few seconds after death; everything respawns on rest
 * (see {@link EnemyManager#resetAll()}).
 */
public class Enemy implements Combatant {

    /** The two enemy archetypes; stats are 1:1 with ENEMY_TYPES in enemies.js. */
    public enum Type { LEGIONARIUS, PRAETORIANUS }

    /** Current AI state: idle|chase|strafe|attack|stagger|dead|gone. */
    public String state;
    /** Set true once on the death frame; consumed (cleared) by EnemyManager. */
    public boolean justDied;

    // ------------------------------------------------------------- stat table
    private static final class Cfg {
        final float hp, speed, reach, aggro, attackCd, scale, swordLen;
        final int dmg, gloria, poiseMax;
        final ColorRGBA tunic, skin, crest;
        final String helmet;

        Cfg(float hp, float speed, int dmg, float reach, float aggro, int gloria,
            int poiseMax, float attackCd, float scale,
            int tunic, int skin, int crest, String helmet, float swordLen) {
            this.hp = hp; this.speed = speed; this.dmg = dmg; this.reach = reach;
            this.aggro = aggro; this.gloria = gloria; this.poiseMax = poiseMax;
            this.attackCd = attackCd; this.scale = scale;
            this.tunic = rgb(tunic); this.skin = rgb(skin); this.crest = rgb(crest);
            this.helmet = helmet; this.swordLen = swordLen;
        }
    }

    private static final Cfg LEGIONARIUS_CFG = new Cfg(
            70, 3.6f, 16, 2.2f, 15, 35, 50, 1.4f, 1f,
            0x5a1414, 0x9a7a5c, 0xa01818, "legionary", 0.8f);
    private static final Cfg PRAETORIANUS_CFG = new Cfg(
            160, 3.0f, 26, 2.5f, 14, 110, 110, 1.9f, 1.18f,
            0x2a1a3a, 0x8a6a50, 0xa01818, "centurion", 0.95f);

    // -------------------------------------------------- deterministic enemy RNG
    // Shared Lehmer LCG, identical sequence to enemies.js (module seed = 7).
    private static long seed = 7;

    private static float erand() {
        seed = (seed * 16807L) % 2147483647L;
        return (float) ((seed - 1) / 2147483646.0);
    }

    private static ColorRGBA rgb(int hex) {
        return new ColorRGBA(((hex >> 16) & 0xff) / 255f,
                ((hex >> 8) & 0xff) / 255f, (hex & 0xff) / 255f, 1f);
    }

    // ------------------------------------------------------------------ fields
    private final GameCtx ctx;
    private final Type type;
    private final Cfg cfg;
    private final Vector3f spawn;
    private final float patrolR;
    private final Rig rig;
    private final Vector3f position = new Vector3f();
    private final float radius;
    private final Quaternion tmpRot = new Quaternion();

    private float hp;
    private float poise;
    private float time;
    private float stateT;
    private float yaw;
    private float attackCd;
    private float waitT;
    private Vector3f wanderTarget;
    private int strafeDir;
    private String attackKind;
    private float attackDur, hitA, hitB;
    private boolean didHit;
    private boolean alertNearby;
    private float walkCycle;

    /**
     * Builds the rig, attaches it to the scene and spawns the enemy at its
     * patrol anchor. {@code spawn.y} is ignored — height comes from the terrain.
     */
    public Enemy(GameCtx ctx, Type type, Vector3f spawn, float patrolR) {
        this.ctx = ctx;
        this.type = type;
        this.cfg = (type == Type.LEGIONARIUS) ? LEGIONARIUS_CFG : PRAETORIANUS_CFG;
        this.spawn = spawn.clone();
        this.patrolR = patrolR;

        Rig.Options o = new Rig.Options();
        o.scale = cfg.scale;
        o.tunic = cfg.tunic;
        o.skin = cfg.skin;
        o.crest = cfg.crest;
        o.helmet = cfg.helmet;
        o.shield = true;
        o.swordLen = cfg.swordLen;
        this.rig = Rig.humanoid(ctx, o);
        ctx.rootNode.attachChild(rig.root);

        this.radius = 0.5f * cfg.scale;
        this.time = erand() * 10f;
        this.walkCycle = 0;
        reset();
    }

    /** Back to spawn at full health, idle and visible (called on shrine rest). */
    public void reset() {
        position.set(spawn);
        position.y = ctx.world.getHeight(position.x, position.z);
        hp = cfg.hp;
        poise = 0;
        state = "idle";
        stateT = 0;
        yaw = erand() * FastMath.TWO_PI;
        attackCd = 0;
        waitT = 1 + erand() * 3;
        wanderTarget = null;
        strafeDir = 1;
        attackKind = "light1";
        didHit = false;
        justDied = false;
        alertNearby = false;
        rig.root.setCullHint(Spatial.CullHint.Inherit);
        rig.setFade(1f);
        rig.root.setLocalTranslation(position);
        rig.root.setLocalRotation(tmpRot.fromAngles(0, yaw, 0));
    }

    /** Gloria granted to the player when this enemy dies. */
    public int gloriaReward() {
        return cfg.gloria;
    }

    /** Wakes the enemy (idle -&gt; chase) and flags a pack alert to nearby allies. */
    public void aggro() {
        if ("idle".equals(state)) {
            state = "chase";
            stateT = 0;
            alertNearby = true;
        }
    }

    // ------------------------------------------------------------- Combatant
    @Override
    public Vector3f pos() {
        return position;
    }

    @Override
    public boolean isAlive() {
        return !"dead".equals(state) && !"gone".equals(state);
    }

    @Override
    public float radius() {
        return radius;
    }

    @Override
    public void takeDamage(int amount, int poiseDmg, Vector3f from) {
        if (!isAlive()) return;
        hp -= amount;
        poise += poiseDmg;
        aggro();
        if (hp <= 0) {
            state = "dead";
            stateT = 0;
            justDied = true;
            ctx.audio.enemyDie();
            ctx.fx.soul(new Vector3f(position.x, position.y + 1f, position.z));
            return;
        }
        if (poise >= cfg.poiseMax) {
            poise = 0;
            state = "stagger";
            stateT = 0;
        }
    }

    // ---------------------------------------------------------------- update
    /** Per-frame AI tick. Player/allies/fog state are read from the context. */
    public void update(float tpf) {
        time += tpf;
        stateT += tpf;
        attackCd -= tpf;

        PlayerCtrl player = ctx.player;
        float tox = player.position.x - position.x;
        float toz = player.position.z - position.z;
        float dist = FastMath.sqrt(tox * tox + toz * toz);
        boolean playerTargetable = player.isAlive() && !"rest".equals(player.action);
        java.util.List<Enemy> all = ctx.enemies.list;

        float moving = 0;

        switch (state) {
            case "idle": {
                if (playerTargetable && dist < cfg.aggro) {
                    aggro();
                    break;
                }
                // lazy patrol around the spawn anchor
                waitT -= tpf;
                if (waitT <= 0 && wanderTarget == null) {
                    float a = erand() * FastMath.TWO_PI;
                    wanderTarget = new Vector3f(
                            spawn.x + FastMath.cos(a) * patrolR * erand(), 0,
                            spawn.z + FastMath.sin(a) * patrolR * erand());
                }
                if (wanderTarget != null) {
                    float wx = wanderTarget.x - position.x;
                    float wz = wanderTarget.z - position.z;
                    if (FastMath.sqrt(wx * wx + wz * wz) < 0.6f) {
                        wanderTarget = null;
                        waitT = 2 + erand() * 4;
                    } else {
                        faceToward(wx, wz, tpf, 4);
                        stepForward(cfg.speed * 0.4f, tpf);
                        moving = 0.4f;
                    }
                }
                break;
            }
            case "chase": {
                if (!playerTargetable) {
                    state = "idle";
                    wanderTarget = null;
                    break;
                }
                if (dist > cfg.aggro * 2.6f && stateT > 6) {
                    state = "idle";
                    break;
                }
                faceToward(tox, toz, tpf, 7);
                if (dist > cfg.reach * 0.85f) {
                    stepForward(cfg.speed, tpf);
                    moving = 1;
                } else if (attackCd <= 0) {
                    beginAttack();
                } else {
                    state = "strafe";
                    stateT = 0;
                    strafeDir = erand() < 0.5f ? 1 : -1;
                }
                break;
            }
            case "strafe": {
                if (!playerTargetable) {
                    state = "idle";
                    break;
                }
                faceToward(tox, toz, tpf, 7);
                float k = strafeDir * cfg.speed * 0.45f * tpf;
                position.x += FastMath.cos(yaw) * k;
                position.z += -FastMath.sin(yaw) * k;
                if (dist > cfg.reach * 1.3f) stepForward(cfg.speed * 0.6f, tpf);
                moving = 0.45f;
                if (attackCd <= 0 && dist < cfg.reach * 1.1f) beginAttack();
                else if (stateT > 1.4f) {
                    state = "chase";
                    stateT = 0;
                }
                break;
            }
            case "attack": {
                float ph = stateT / attackDur;
                if (ph > 0.15f && ph < 0.45f) faceToward(tox, toz, tpf, 3); // track during windup
                if (ph > hitA && ph < hitB) {
                    stepForward(2.0f, tpf);
                    if (!didHit && playerTargetable) {
                        float ang = Math.abs(C.angleDiff(FastMath.atan2(tox, toz), yaw));
                        if (dist < cfg.reach + player.radius() && ang < 1.0f) {
                            didHit = true;
                            if (hitPlayer(cfg.dmg)) ctx.audio.clang();
                        }
                    }
                }
                if (ph >= 1) {
                    state = "chase";
                    stateT = 0;
                    attackCd = cfg.attackCd * (0.8f + erand() * 0.5f);
                }
                break;
            }
            case "stagger": {
                if (stateT > 0.7f) {
                    state = "chase";
                    stateT = 0;
                }
                break;
            }
            case "dead": {
                if (stateT > 4) {
                    // fade out the corpse
                    float k = Math.min(1f, (stateT - 4) / 1.5f);
                    rig.setFade(1 - k);
                    if (k >= 1) {
                        state = "gone";
                        rig.root.setCullHint(Spatial.CullHint.Always);
                    }
                }
                break;
            }
            case "gone":
                return;
            default:
                break;
        }

        // pack alert: wake allies close to me
        if (alertNearby) {
            alertNearby = false;
            for (Enemy e : all) {
                if (e != this && e.isAlive()) {
                    float dx = e.position.x - position.x;
                    float dz = e.position.z - position.z;
                    if (dx * dx + dz * dz < 11f * 11f) e.aggro();
                }
            }
        }

        // separation from other enemies, then static collisions + terrain height
        if (isAlive()) {
            for (Enemy e : all) {
                if (e == this || !e.isAlive()) continue;
                float dx = position.x - e.position.x;
                float dz = position.z - e.position.z;
                float d = FastMath.sqrt(dx * dx + dz * dz);
                float minD = radius + e.radius + 0.15f;
                if (d < minD && d > 1e-4f) {
                    position.x += (dx / d) * (minD - d) * 0.5f;
                    position.z += (dz / d) * (minD - d) * 0.5f;
                }
            }
            ctx.world.resolveStatics(position, radius, ctx.game.bossFight);
            position.y = ctx.world.getHeight(position.x, position.z);
        }

        // animation
        if (moving > 0) walkCycle += tpf * cfg.speed * (moving > 0.7f ? 1.8f : 1.1f);
        updateRig(moving);
    }

    // --------------------------------------------------------------- helpers
    private void beginAttack() {
        state = "attack";
        stateT = 0;
        didHit = false;
        // praetorians mix in heavy overheads
        attackKind = (type == Type.PRAETORIANUS && erand() < 0.4f) ? "heavy"
                : (erand() < 0.5f ? "light1" : "light2");
        attackDur = "heavy".equals(attackKind) ? 1.3f : 0.95f;
        hitA = "heavy".equals(attackKind) ? 0.45f : 0.38f;
        hitB = "heavy".equals(attackKind) ? 0.62f : 0.58f;
        ctx.audio.swing();
    }

    /** Applies damage to the player; true if it landed (not i-framed/resting). */
    private boolean hitPlayer(int dmg) {
        float before = ctx.player.hp;
        ctx.player.takeDamage(dmg, 0, position.clone());
        return ctx.player.hp < before;
    }

    private void faceToward(float dirX, float dirZ, float dt, float rate) {
        float target = FastMath.atan2(dirX, dirZ);
        yaw += C.angleDiff(target, yaw) * Math.min(1f, dt * rate);
    }

    private void stepForward(float speed, float dt) {
        position.x += FastMath.sin(yaw) * speed * dt;
        position.z += FastMath.cos(yaw) * speed * dt;
    }

    private void updateRig(float moving) {
        Poses.reset(rig);
        switch (state) {
            case "attack":
                Poses.attack(rig, Math.min(1f, stateT / attackDur), attackKind);
                break;
            case "stagger":
                Poses.stagger(rig, stateT / 0.7f);
                break;
            case "dead":
            case "gone":
                Poses.death(rig, Math.min(1f, stateT / 1.0f));
                break;
            default:
                if (moving > 0) Poses.walk(rig, walkCycle, moving);
                else Poses.idle(rig, time);
        }
        rig.root.setLocalTranslation(position);
        rig.root.setLocalRotation(tmpRot.fromAngles(0, yaw, 0));
    }
}
