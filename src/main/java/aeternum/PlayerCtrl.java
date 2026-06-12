package aeternum;

import com.jme3.input.KeyInput;
import com.jme3.input.MouseInput;
import com.jme3.input.controls.ActionListener;
import com.jme3.input.controls.AnalogListener;
import com.jme3.input.controls.KeyTrigger;
import com.jme3.input.controls.MouseAxisTrigger;
import com.jme3.input.controls.MouseButtonTrigger;
import com.jme3.math.FastMath;
import com.jme3.math.Quaternion;
import com.jme3.math.Vector3f;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Third-person souls-like controller + camera (port of web/src/player.js).
 */
public class PlayerCtrl implements Combatant {

    private static final class Atk {
        final float dur, hitA, hitB, range, arc, dmgMul;
        final int stam, poise;
        final String next;

        Atk(float dur, float hitA, float hitB, float range, float arc,
            float dmgMul, int stam, int poise, String next) {
            this.dur = dur; this.hitA = hitA; this.hitB = hitB; this.range = range;
            this.arc = arc; this.dmgMul = dmgMul; this.stam = stam; this.poise = poise;
            this.next = next;
        }
    }

    private static Atk atk(String kind) {
        return switch (kind) {
            case "light1" -> new Atk(0.55f, 0.34f, 0.54f, 2.4f, 1.25f, 1.0f, 16, 30, "light2");
            case "light2" -> new Atk(0.5f, 0.3f, 0.52f, 2.4f, 1.4f, 1.1f, 16, 30, "light1");
            default -> new Atk(0.95f, 0.42f, 0.6f, 2.6f, 1.1f, 2.1f, 32, 80, null);
        };
    }

    private static final float ROLL_DUR = 0.62f, ROLL_SPEED = 8.6f,
            ROLL_IA = 0.04f, ROLL_IB = 0.62f;
    private static final int ROLL_STAM = 20;

    // ---- public state (read by GameState / enemies / Main) ----
    public int vigor = 8, endurance = 8, strength = 8;
    public int gloria = 0, flasks = 4, flasksMax = 4;
    public float hp, maxHp, stamina, maxStamina;
    public String action = "free";
    public Combatant lockTarget;
    public final Vector3f position = new Vector3f(0f, 0f, 21f);
    public float yaw = FastMath.PI, camYaw = FastMath.PI, camPitch = 0.28f;
    public Rig rig;
    public boolean justDied;
    public List<Combatant> targets = new ArrayList<>();

    private final GameCtx ctx;
    private final Quaternion tmpRot = new Quaternion();
    private final Vector3f camPos = new Vector3f();

    // action state
    private float actionT;
    private String attackKind = "light1";
    private final Set<Combatant> hits = new HashSet<>();
    private boolean comboQueued, drinkHealed;
    private Vector3f rollDir = new Vector3f(0, 0, -1);
    private float staggerDur;

    private float staminaDelay;
    private float walkCycle, moveIntensity;

    // input states + per-frame edges
    private boolean wDown, aDown, sDown, dDown, shiftDown;
    private boolean spaceEdge, lmbEdge, rmbEdge, qEdge, fEdge, eEdge;
    private final boolean[] digitEdge = new boolean[4];
    private float mouseDX, mouseDY;

    public PlayerCtrl(GameCtx ctx) {
        this.ctx = ctx;
        Rig.Options o = new Rig.Options();
        o.tunic = Rig.Options.rgb(0x8b2020);
        o.helmet = "legionary";
        o.shield = true;
        o.swordLen = 0.75f;
        rig = Rig.humanoid(ctx, o);
        ctx.rootNode.attachChild(rig.root);

        recompute();
        hp = maxHp;
        stamina = maxStamina;
        position.y = 0f;

        registerInput();
    }

    public void recompute() {
        maxHp = 40 + vigor * 13;
        maxStamina = 55 + endurance * 6;
    }

    public int lightDmg() {
        return Math.round(12 + strength * 2.4f);
    }

    // ------------------------------------------------------------- input

    private void registerInput() {
        var in = ctx.input;
        in.addMapping("P_W", new KeyTrigger(KeyInput.KEY_W));
        in.addMapping("P_A", new KeyTrigger(KeyInput.KEY_A));
        in.addMapping("P_S", new KeyTrigger(KeyInput.KEY_S));
        in.addMapping("P_D", new KeyTrigger(KeyInput.KEY_D));
        in.addMapping("P_SHIFT", new KeyTrigger(KeyInput.KEY_LSHIFT), new KeyTrigger(KeyInput.KEY_RSHIFT));
        in.addMapping("P_SPACE", new KeyTrigger(KeyInput.KEY_SPACE));
        in.addMapping("P_Q", new KeyTrigger(KeyInput.KEY_Q));
        in.addMapping("P_E", new KeyTrigger(KeyInput.KEY_E));
        in.addMapping("P_F", new KeyTrigger(KeyInput.KEY_F));
        in.addMapping("P_1", new KeyTrigger(KeyInput.KEY_1));
        in.addMapping("P_2", new KeyTrigger(KeyInput.KEY_2));
        in.addMapping("P_3", new KeyTrigger(KeyInput.KEY_3));
        in.addMapping("P_LMB", new MouseButtonTrigger(MouseInput.BUTTON_LEFT));
        in.addMapping("P_RMB", new MouseButtonTrigger(MouseInput.BUTTON_RIGHT));
        in.addMapping("P_MXP", new MouseAxisTrigger(MouseInput.AXIS_X, false));
        in.addMapping("P_MXN", new MouseAxisTrigger(MouseInput.AXIS_X, true));
        in.addMapping("P_MYP", new MouseAxisTrigger(MouseInput.AXIS_Y, false));
        in.addMapping("P_MYN", new MouseAxisTrigger(MouseInput.AXIS_Y, true));

        ActionListener keys = (name, pressed, tpf) -> {
            switch (name) {
                case "P_W" -> wDown = pressed;
                case "P_A" -> aDown = pressed;
                case "P_S" -> sDown = pressed;
                case "P_D" -> dDown = pressed;
                case "P_SHIFT" -> shiftDown = pressed;
                case "P_SPACE" -> { if (pressed) spaceEdge = true; }
                case "P_Q" -> { if (pressed) qEdge = true; }
                case "P_E" -> { if (pressed) eEdge = true; }
                case "P_F" -> { if (pressed) fEdge = true; }
                case "P_1" -> { if (pressed) digitEdge[1] = true; }
                case "P_2" -> { if (pressed) digitEdge[2] = true; }
                case "P_3" -> { if (pressed) digitEdge[3] = true; }
                case "P_LMB" -> { if (pressed) lmbEdge = true; }
                case "P_RMB" -> { if (pressed) rmbEdge = true; }
            }
        };
        ctx.input.addListener(keys, "P_W", "P_A", "P_S", "P_D", "P_SHIFT", "P_SPACE",
                "P_Q", "P_E", "P_F", "P_1", "P_2", "P_3", "P_LMB", "P_RMB");

        AnalogListener mouse = (name, value, tpf) -> {
            float px = value * 1024f;
            switch (name) {
                case "P_MXP" -> mouseDX += px;
                case "P_MXN" -> mouseDX -= px;
                case "P_MYP" -> mouseDY -= px; // mouse up -> look up
                case "P_MYN" -> mouseDY += px;
            }
        };
        ctx.input.addListener(mouse, "P_MXP", "P_MXN", "P_MYP", "P_MYN");
    }

    /** Read-and-clear the E edge (GameState interaction). */
    public boolean consumeInteract() {
        boolean v = eEdge;
        eEdge = false;
        return v;
    }

    /** Read-and-clear a level-up digit edge (1..3). */
    public boolean consumeLevelKey(int n) {
        boolean v = digitEdge[n];
        digitEdge[n] = false;
        return v;
    }

    /** Called by Main at the very end of each frame. */
    public void clearEdges() {
        spaceEdge = lmbEdge = rmbEdge = qEdge = fEdge = eEdge = false;
        digitEdge[1] = digitEdge[2] = digitEdge[3] = false;
    }

    // ------------------------------------------------------------- update

    public void update(float tpf) {
        actionT += tpf;

        // camera orbit from accumulated mouse deltas
        float dx = mouseDX, dy = mouseDY;
        mouseDX = mouseDY = 0f;
        if (lockTarget == null) {
            camYaw -= dx * 0.0026f;
            camPitch = FastMath.clamp(camPitch + dy * 0.0022f, -0.25f, 1.25f);
        }

        if (ctx.game.resting) {
            updateRig();
            return;
        }

        // lock-on toggle / validation
        if (qEdge) { qEdge = false; toggleLock(); }
        if (lockTarget != null
                && (!lockTarget.isAlive() || lockTarget.pos().distance(position) > 34f)) {
            lockTarget = null;
        }
        if (lockTarget != null) {
            Vector3f t = lockTarget.pos();
            float desired = FastMath.atan2(position.x - t.x, position.z - t.z);
            camYaw += C.angleDiff(desired, camYaw) * Math.min(1f, tpf * 5f);
            camPitch += (0.3f - camPitch) * Math.min(1f, tpf * 3f);
        }

        // stamina regen
        staminaDelay -= tpf;
        if (staminaDelay <= 0f && !action.equals("dead")) {
            stamina = Math.min(maxStamina, stamina + (30 + endurance) * tpf);
        }

        // camera-space move input
        float mx = (dDown ? 1 : 0) - (aDown ? 1 : 0);
        float mz = (wDown ? 1 : 0) - (sDown ? 1 : 0);
        boolean hasMove = mx != 0 || mz != 0;
        Vector3f fwd = new Vector3f(-FastMath.sin(camYaw), 0f, -FastMath.cos(camYaw));
        Vector3f right = new Vector3f(-fwd.z, 0f, fwd.x);
        Vector3f moveDir = new Vector3f();
        if (hasMove) {
            moveDir.set(fwd).multLocal(mz).addLocal(right.mult(mx)).normalizeLocal();
        }

        switch (action) {
            case "free" -> updateFree(tpf, moveDir, hasMove);
            case "roll" -> {
                float ph = actionT / ROLL_DUR;
                moveAlong(rollDir, ROLL_SPEED * (1f - ph * 0.45f), tpf);
                if (ph >= 1f) startAction("free");
            }
            case "attack" -> updateAttack(tpf);
            case "drink" -> {
                if (hasMove) moveAlong(moveDir, 1.2f, tpf);
                float ph = actionT / 1.3f;
                if (ph > 0.55f && !drinkHealed) {
                    drinkHealed = true;
                    hp = Math.min(maxHp, hp + Math.round(maxHp * 0.6f));
                    ctx.audio.heal();
                    ctx.fx.soul(position.add(0f, 1.2f, 0f));
                }
                if (ph >= 1f) startAction("free");
            }
            case "stagger" -> {
                if (actionT >= staggerDur) startAction("free");
            }
            default -> { /* dead / rest: nothing */ }
        }

        ctx.world.resolveStatics(position, radius(), ctx.game.bossFight);
        position.y = ctx.world.getHeight(position.x, position.z);
        updateRig();
    }

    private void startAction(String name) {
        action = name;
        actionT = 0f;
    }

    private void spendStamina(float v) {
        stamina -= v;
        staminaDelay = 0.7f;
    }

    private void updateFree(float tpf, Vector3f moveDir, boolean hasMove) {
        if (spaceEdge && stamina > 0f) {
            spaceEdge = false;
            rollDir = hasMove ? moveDir.clone() : facing();
            spendStamina(ROLL_STAM);
            startAction("roll");
            yaw = FastMath.atan2(rollDir.x, rollDir.z);
            ctx.audio.roll();
            return;
        }
        if (lmbEdge && stamina > 0f) { lmbEdge = false; beginAttack("light1"); return; }
        if (rmbEdge && stamina > 0f) { rmbEdge = false; beginAttack("heavy"); return; }
        if (fEdge && flasks > 0 && hp < maxHp) {
            fEdge = false;
            flasks--;
            drinkHealed = false;
            startAction("drink");
            return;
        }

        boolean sprinting = shiftDown && hasMove && stamina > 0f;
        if (sprinting) spendStamina(11f * tpf);
        float speed = sprinting ? 7.2f : 4.4f;
        if (hasMove) {
            moveAlong(moveDir, speed, tpf);
            float targetYaw = (lockTarget != null && !sprinting)
                    ? FastMath.atan2(lockTarget.pos().x - position.x, lockTarget.pos().z - position.z)
                    : FastMath.atan2(moveDir.x, moveDir.z);
            yaw += C.angleDiff(targetYaw, yaw) * Math.min(1f, tpf * 12f);
            float prev = walkCycle;
            walkCycle += tpf * speed * 1.65f;
            moveIntensity = FastMath.interpolateLinear(Math.min(1f, tpf * 6f),
                    moveIntensity, sprinting ? 1f : 0.45f);
            if ((int) (prev / FastMath.PI) != (int) (walkCycle / FastMath.PI)) ctx.audio.step();
        } else {
            moveIntensity = FastMath.interpolateLinear(Math.min(1f, tpf * 8f), moveIntensity, 0f);
            if (lockTarget != null) {
                float t = FastMath.atan2(lockTarget.pos().x - position.x,
                        lockTarget.pos().z - position.z);
                yaw += C.angleDiff(t, yaw) * Math.min(1f, tpf * 8f);
            }
        }
    }

    private void beginAttack(String kind) {
        Atk a = atk(kind);
        spendStamina(a.stam);
        attackKind = kind;
        hits.clear();
        comboQueued = false;
        startAction("attack");
        if (lockTarget != null) {
            yaw = FastMath.atan2(lockTarget.pos().x - position.x, lockTarget.pos().z - position.z);
        }
        if (kind.equals("heavy")) ctx.audio.swingHeavy(); else ctx.audio.swing();
    }

    private void updateAttack(float tpf) {
        Atk a = atk(attackKind);
        float ph = actionT / a.dur;

        if (lmbEdge && a.next != null && ph > 0.35f) {
            lmbEdge = false;
            comboQueued = true;
        }

        if (ph > a.hitA && ph < a.hitB) {
            moveAlong(facing(), 2.2f, tpf);
            for (Combatant t : targets) {
                if (!t.isAlive() || hits.contains(t)) continue;
                float dxz = FastMath.sqrt(
                        FastMath.sqr(t.pos().x - position.x) + FastMath.sqr(t.pos().z - position.z));
                if (dxz > a.range + t.radius()) continue;
                float ang = Math.abs(C.angleDiff(
                        FastMath.atan2(t.pos().x - position.x, t.pos().z - position.z), yaw));
                if (ang > a.arc) continue;
                hits.add(t);
                t.takeDamage(Math.round(lightDmg() * a.dmgMul), a.poise, position);
                ctx.audio.hit();
                ctx.fx.blood(t.pos().add(0f, 1.3f, 0f));
            }
        }

        // roll-cancel late in the swing
        if (ph > 0.7f && spaceEdge && stamina > 0f) {
            spaceEdge = false;
            rollDir = facing();
            spendStamina(ROLL_STAM);
            startAction("roll");
            ctx.audio.roll();
            return;
        }

        if (ph >= 1f) {
            if (comboQueued && a.next != null && stamina > 0f) beginAttack(a.next);
            else startAction("free");
        }
    }

    private void moveAlong(Vector3f dir, float speed, float tpf) {
        position.x += dir.x * speed * tpf;
        position.z += dir.z * speed * tpf;
    }

    private Vector3f facing() {
        return new Vector3f(FastMath.sin(yaw), 0f, FastMath.cos(yaw));
    }

    private void toggleLock() {
        if (lockTarget != null) { lockTarget = null; return; }
        Combatant best = null;
        float bestScore = Float.MAX_VALUE;
        for (Combatant t : targets) {
            if (!t.isAlive()) continue;
            float d = t.pos().distance(position);
            if (d > 28f) continue;
            float ang = Math.abs(C.angleDiff(
                    FastMath.atan2(t.pos().x - position.x, t.pos().z - position.z),
                    camYaw + FastMath.PI));
            float score = d + ang * 6f;
            if (score < bestScore) { bestScore = score; best = t; }
        }
        lockTarget = best;
    }

    // ------------------------------------------------------------- combat

    @Override
    public Vector3f pos() { return position; }

    @Override
    public boolean isAlive() { return !action.equals("dead"); }

    @Override
    public float radius() { return 0.45f; }

    @Override
    public void takeDamage(int amount, int poiseDmg, Vector3f from) {
        if (!isAlive()) return;
        if (action.equals("roll")) {
            float ph = actionT / ROLL_DUR;
            if (ph > ROLL_IA && ph < ROLL_IB) return; // i-frames
        }
        if (action.equals("rest")) return;
        hp -= amount;
        ctx.hud.hurtFlash();
        ctx.fx.blood(position.add(0f, 1.3f, 0f));
        if (hp <= 0f) {
            hp = 0f;
            startAction("dead");
            lockTarget = null;
            justDied = true;
            ctx.audio.death();
            return;
        }
        ctx.audio.hurt();
        if (amount > maxHp * 0.18f && action.equals("free")) {
            staggerDur = 0.45f;
            startAction("stagger");
        }
    }

    public void respawn(Vector3f at) {
        position.set(at);
        position.y = ctx.world.getHeight(at.x, at.z);
        hp = maxHp;
        stamina = maxStamina;
        flasks = flasksMax;
        startAction("free");
        lockTarget = null;
        yaw = FastMath.PI;
        camYaw = FastMath.PI;
    }

    public void startRest() { startAction("rest"); }

    public void standUp() { startAction("free"); }

    // ------------------------------------------------------------- visuals

    private void updateRig() {
        Poses.reset(rig);
        switch (action) {
            case "free" -> {
                if (moveIntensity > 0.03f) Poses.walk(rig, walkCycle, moveIntensity);
                else Poses.idle(rig, ctx.time);
            }
            case "roll" -> Poses.roll(rig, Math.min(1f, actionT / ROLL_DUR));
            case "attack" -> Poses.attack(rig, Math.min(1f, actionT / atk(attackKind).dur), attackKind);
            case "drink" -> Poses.drink(rig, Math.min(1f, actionT / 1.3f));
            case "stagger" -> Poses.stagger(rig, actionT / staggerDur);
            case "dead" -> Poses.death(rig, Math.min(1f, actionT / 1.2f));
            case "rest" -> Poses.rest(rig);
        }
        rig.root.setLocalTranslation(position);
        rig.root.setLocalRotation(tmpRot.fromAngles(0f, yaw, 0f));
    }

    public void updateCamera(float tpf) {
        Vector3f target = new Vector3f(position.x, position.y + 1.55f, position.z);
        Vector3f off = new Vector3f(
                FastMath.sin(camYaw) * FastMath.cos(camPitch),
                FastMath.sin(camPitch),
                FastMath.cos(camYaw) * FastMath.cos(camPitch)).multLocal(5.6f);
        Vector3f desired = target.add(off);
        float minY = ctx.world.getHeight(desired.x, desired.z) + 0.4f;
        if (desired.y < minY) desired.y = minY;
        camPos.interpolateLocal(desired, Math.min(1f, tpf * 14f));
        ctx.cam.setLocation(camPos);
        Vector3f look = target.clone();
        if (lockTarget != null) {
            Vector3f lt = lockTarget.pos().clone();
            lt.y = lockTarget.pos().y + 1.6f;
            look.interpolateLocal(lt, 0.35f);
        }
        ctx.cam.lookAt(look, Vector3f.UNIT_Y);
    }
}
