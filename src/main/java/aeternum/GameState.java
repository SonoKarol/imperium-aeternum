package aeternum;

import com.jme3.input.KeyInput;
import com.jme3.input.MouseInput;
import com.jme3.input.controls.ActionListener;
import com.jme3.input.controls.KeyTrigger;
import com.jme3.input.controls.MouseButtonTrigger;
import com.jme3.light.PointLight;
import com.jme3.material.Material;
import com.jme3.math.ColorRGBA;
import com.jme3.math.FastMath;
import com.jme3.math.Vector3f;
import com.jme3.renderer.queue.RenderQueue;
import com.jme3.scene.Geometry;
import com.jme3.scene.Node;
import com.jme3.scene.shape.Cylinder;

/**
 * High-level game flow: title screen, shrine rest, death/respawn, gloria
 * marker, boss trigger and victory. Port of the flow in web/src/main.js.
 */
public class GameState {
    public enum Phase { TITLE, PLAYING }

    public Phase phase = Phase.TITLE;
    public boolean paused = false;
    /** True while the player kneels at the shrine (level-up panel open). */
    public boolean resting = false;
    /** True while the boss fight seals the arena gate. */
    public boolean bossFight = false;

    private final GameCtx ctx;
    private float deadT = -1f;

    // pending one-frame input edges captured by our own listeners
    private boolean clickEdge, escEdge, wipeEdge;

    // gloria dropped on death
    private Node marker;
    private PointLight markerLight;
    private int markerAmount;

    public GameState(GameCtx ctx) {
        this.ctx = ctx;
        ActionListener l = (name, pressed, tpf) -> {
            if (!pressed) return;
            switch (name) {
                case "GS_CLICK" -> clickEdge = true;
                case "GS_ESC" -> escEdge = true;
                case "GS_WIPE" -> wipeEdge = true;
            }
        };
        ctx.input.addMapping("GS_CLICK", new MouseButtonTrigger(MouseInput.BUTTON_LEFT));
        ctx.input.addMapping("GS_ESC", new KeyTrigger(KeyInput.KEY_ESCAPE));
        ctx.input.addMapping("GS_WIPE", new KeyTrigger(KeyInput.KEY_N));
        ctx.input.addListener(l, "GS_CLICK", "GS_ESC", "GS_WIPE");
    }

    public void startGame() {
        phase = Phase.PLAYING;
        paused = false;
        ctx.input.setCursorVisible(false);
        ctx.hud.showTitle(false);
        ctx.audio.startAmbient();
        ctx.hud.message("Le ceneri di Roma ti attendono", new ColorRGBA(0.85f, 0.82f, 0.75f, 1f),
                3.5f, "cerca il Sacrarium lungo la Via Sacra");
    }

    public void update(float tpf, GameCtx ctx) {
        boolean click = clickEdge, esc = escEdge, wipe = wipeEdge;
        clickEdge = escEdge = wipeEdge = false;

        if (phase == Phase.TITLE) {
            ctx.input.setCursorVisible(true);
            ctx.hud.showTitle(true);
            if (wipe) {
                Save.wipe();
                ctx.player.vigor = ctx.player.endurance = ctx.player.strength = 8;
                ctx.player.gloria = 0;
                ctx.player.recompute();
                ctx.player.hp = ctx.player.maxHp;
                ctx.boss.defeated = false;
                ctx.boss.reset();
                ctx.hud.message("SALVATAGGIO CANCELLATO", ColorRGBA.Gray, 2f, "");
            }
            if (click) startGame();
            updateTitleCamera();
            return;
        }

        // ---- pause ----
        if (esc && deadT < 0) {
            paused = !paused;
            ctx.input.setCursorVisible(paused);
            ctx.hud.showPaused(paused);
        }
        if (paused) {
            if (click) {
                paused = false;
                ctx.input.setCursorVisible(false);
                ctx.hud.showPaused(false);
            }
            return;
        }

        // ---- death / respawn ----
        if (ctx.player.justDied) {
            ctx.player.justDied = false;
            onPlayerDeath();
        }
        if (deadT >= 0) {
            deadT += tpf;
            ctx.hud.showDeathFade(FastMath.clamp(deadT / 2.5f, 0f, 0.85f));
            if (deadT > 3.4f) {
                deadT = -1f;
                ctx.hud.showDeathFade(0f);
                Vector3f at = ctx.world.shrinePos.clone();
                at.x += 1.8f;
                ctx.player.respawn(at);
                ctx.enemies.resetAll();
            }
        }

        // ---- gloria from kills ----
        int earned = ctx.enemies.collectGloria();
        if (earned > 0) {
            ctx.player.gloria += earned;
            ctx.audio.pickup();
        }
        if (ctx.boss.justDied) {
            ctx.boss.justDied = false;
            endBossFight(true);
        }

        // ---- boss trigger ----
        if (!ctx.boss.defeated && !bossFight && ctx.player.isAlive()) {
            float d = FastMath.sqrt(FastMath.sqr(ctx.player.position.x - C.ARENA_X)
                    + FastMath.sqr(ctx.player.position.z - C.ARENA_Z));
            if (d < C.ARENA_R_IN - 3f) startBossFight();
        }
        if (bossFight) {
            ctx.hud.bossBar(Boss.NAME, ctx.boss.hp / ctx.boss.maxHp);
        }

        // ---- interactions ----
        boolean e = ctx.player.consumeInteract();
        String prompt = null;
        if (ctx.player.isAlive() && !resting) {
            if (marker != null && ctx.player.position.distance(marker.getLocalTranslation()) < 2.4f) {
                prompt = "[E]  recupera la gloria perduta";
                if (e) collectMarker();
            } else if (atShrine() && !bossFight) {
                prompt = "[E]  riposa al Sacrarium";
                if (e) rest();
            }
        } else if (resting) {
            prompt = "[1/2/3]  offri gloria   —   [E]  alzati";
            if (ctx.player.consumeLevelKey(1)) tryLevelUp(0);
            if (ctx.player.consumeLevelKey(2)) tryLevelUp(1);
            if (ctx.player.consumeLevelKey(3)) tryLevelUp(2);
            if (e) standUp();
            ctx.hud.levelPanel(true, ctx.player.vigor, ctx.player.endurance, ctx.player.strength,
                    levelCost(), ctx.player.gloria >= levelCost());
        }
        ctx.hud.setPrompt(prompt);
        if (!resting) ctx.hud.levelPanel(false, 0, 0, 0, 0, false);

        // ---- HUD ----
        ctx.hud.setVitals(ctx.player.hp, ctx.player.maxHp, ctx.player.stamina, ctx.player.maxStamina);
        ctx.hud.setFlasks(ctx.player.flasks, ctx.player.flasksMax);
        ctx.hud.setGloria(ctx.player.gloria);
        ctx.hud.lowHpVignette(ctx.player.isAlive() && ctx.player.hp < ctx.player.maxHp * 0.3f);
        if (ctx.player.lockTarget != null && ctx.player.lockTarget.isAlive()) {
            ctx.hud.lockonAt(ctx.player.lockTarget.pos().add(0f, 1.5f, 0f));
        } else {
            ctx.hud.lockonAt(null);
        }

        // ---- marker shimmer ----
        if (marker != null) {
            marker.rotate(0f, tpf * 0.8f, 0f);
            markerLight.setRadius(9f + FastMath.sin(ctx.time * 3f) * 1.5f);
        }
    }

    // ------------------------------------------------------------ title cam
    private void updateTitleCamera() {
        float a = ctx.time * 0.08f;
        Vector3f s = ctx.world.shrinePos;
        ctx.cam.setLocation(new Vector3f(
                s.x + FastMath.sin(a) * 14f,
                s.y + 5.5f,
                s.z + FastMath.cos(a) * 14f));
        ctx.cam.lookAt(s.add(0f, 2f, 0f), Vector3f.UNIT_Y);
    }

    // ------------------------------------------------------------ shrine
    private boolean atShrine() {
        return ctx.player.position.distance(ctx.world.shrinePos) < 3.4f;
    }

    private void rest() {
        resting = true;
        ctx.player.startRest();
        ctx.player.hp = ctx.player.maxHp;
        ctx.player.stamina = ctx.player.maxStamina;
        ctx.player.flasks = ctx.player.flasksMax;
        ctx.player.lockTarget = null;
        ctx.enemies.resetAll();
        if (!ctx.boss.defeated) ctx.boss.reset();
        ctx.audio.rest();
        ctx.hud.message("REQVIESCIS", new ColorRGBA(0.79f, 0.64f, 0.15f, 1f), 2.2f,
                "il fuoco sacro rinnova le tue forze");
        Save.store(ctx.player, ctx.boss);
    }

    private void standUp() {
        resting = false;
        ctx.player.standUp();
        ctx.hud.levelPanel(false, 0, 0, 0, 0, false);
    }

    private int levelCost() {
        int total = ctx.player.vigor + ctx.player.endurance + ctx.player.strength - 24;
        return (int) Math.floor(70 * Math.pow(1.22, total));
    }

    private void tryLevelUp(int stat) {
        int cost = levelCost();
        if (ctx.player.gloria < cost) return;
        ctx.player.gloria -= cost;
        switch (stat) {
            case 0 -> ctx.player.vigor++;
            case 1 -> ctx.player.endurance++;
            case 2 -> ctx.player.strength++;
        }
        ctx.player.recompute();
        ctx.player.hp = ctx.player.maxHp;
        ctx.player.stamina = ctx.player.maxStamina;
        ctx.audio.levelup();
        Save.store(ctx.player, ctx.boss);
    }

    // ------------------------------------------------------------ boss flow
    private void startBossFight() {
        bossFight = true;
        ctx.boss.awaken(ctx.player.position);
        ctx.world.fogWall.setCullHint(com.jme3.scene.Spatial.CullHint.Never);
        ctx.audio.startBossMusic();
        ctx.hud.message("CENTVRIO INVICTVS", new ColorRGBA(0.85f, 0.82f, 0.75f, 1f), 3f,
                "Custos Aeternus — l'ultimo soldato di Roma");
    }

    private void endBossFight(boolean victory) {
        bossFight = false;
        ctx.world.fogWall.setCullHint(com.jme3.scene.Spatial.CullHint.Always);
        ctx.audio.stopBossMusic();
        ctx.hud.bossBar(null, 0f);
        if (victory) {
            ctx.player.gloria += ctx.boss.gloria;
            ctx.audio.victory();
            ctx.hud.message("GLORIA AETERNA", new ColorRGBA(0.91f, 0.75f, 0.31f, 1f), 6f,
                    "il Custode è caduto — l'Impero riconosce il suo erede");
            Save.store(ctx.player, ctx.boss);
        } else if (!ctx.boss.defeated) {
            ctx.boss.reset();
        }
    }

    // ------------------------------------------------------------ death
    private void onPlayerDeath() {
        deadT = 0f;
        ctx.hud.message("MORTVVS ES", new ColorRGBA(0.63f, 0.09f, 0.09f, 1f), 3.2f, "");
        dropGloria(ctx.player.position.clone(), ctx.player.gloria);
        ctx.player.gloria = 0;
        if (bossFight) endBossFight(false);
        ctx.hud.levelPanel(false, 0, 0, 0, 0, false);
        resting = false;
    }

    private void dropGloria(Vector3f pos, int amount) {
        removeMarker();
        if (amount <= 0) return;
        marker = new Node("gloria-marker");
        Geometry beam = new Geometry("beam", new Cylinder(2, 14, 0.27f, 5f, true));
        Material m = ctx.tex.glow(new ColorRGBA(0.91f, 0.75f, 0.31f, 0.45f), 2.5f);
        m.getAdditionalRenderState().setBlendMode(com.jme3.material.RenderState.BlendMode.Alpha);
        beam.setMaterial(m);
        beam.setQueueBucket(RenderQueue.Bucket.Transparent);
        beam.rotate(FastMath.HALF_PI, 0f, 0f);
        beam.setLocalTranslation(0f, 2.5f, 0f);
        beam.setShadowMode(RenderQueue.ShadowMode.Off);
        marker.attachChild(beam);
        markerLight = new PointLight(pos.add(0f, 1.5f, 0f),
                new ColorRGBA(0.91f, 0.75f, 0.31f, 1f).mult(6f), 10f);
        ctx.rootNode.addLight(markerLight);
        marker.setLocalTranslation(pos);
        ctx.rootNode.attachChild(marker);
        markerAmount = amount;
    }

    private void collectMarker() {
        ctx.player.gloria += markerAmount;
        ctx.fx.soul(marker.getLocalTranslation().add(0f, 1f, 0f));
        ctx.audio.pickup();
        removeMarker();
    }

    private void removeMarker() {
        if (marker != null) {
            ctx.rootNode.detachChild(marker);
            ctx.rootNode.removeLight(markerLight);
            marker = null;
            markerLight = null;
        }
    }
}
