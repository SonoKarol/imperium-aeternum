package aeternum;

import com.jme3.font.BitmapFont;
import com.jme3.font.BitmapText;
import com.jme3.font.Rectangle;
import com.jme3.material.Material;
import com.jme3.material.RenderState.BlendMode;
import com.jme3.math.ColorRGBA;
import com.jme3.math.FastMath;
import com.jme3.math.Vector3f;
import com.jme3.scene.Geometry;
import com.jme3.scene.Mesh;
import com.jme3.scene.Node;
import com.jme3.scene.Spatial.CullHint;
import com.jme3.scene.VertexBuffer.Type;
import com.jme3.scene.shape.Quad;

/**
 * Screen-space HUD (port of {@code web/src/hud.js} + the overlay markup in
 * {@code web/index.html}): vitals bars, flask/gloria counters, prompts, timed
 * center messages, boss bar, hurt/low-hp vignette, lock-on marker, the shrine
 * level-up panel, title screen, pause overlay and the death fade.
 *
 * <p>Everything is built once in the constructor on {@code ctx.guiNode} and
 * mutated afterwards (visibility, scale, text) — nothing is recreated per
 * frame. All sizes derive from {@code cam.getWidth()} so the layout scales
 * with resolution (1280 px reference width, like the CSS pixel values).</p>
 */
public class Hud {

    // ------------------------------------------------------------- palette
    private static final ColorRGBA GOLD     = new ColorRGBA(0.788f, 0.635f, 0.153f, 1f); // #c9a227
    private static final ColorRGBA GOLD_DIM = new ColorRGBA(0.541f, 0.439f, 0.098f, 1f); // #8a7019
    private static final ColorRGBA BONE     = new ColorRGBA(0.847f, 0.816f, 0.753f, 1f); // #d8d0c0
    private static final ColorRGBA INK      = new ColorRGBA(0.031f, 0.024f, 0.016f, 0.82f);
    private static final ColorRGBA BORDER   = new ColorRGBA(0.290f, 0.235f, 0.125f, 1f); // #4a3c20
    private static final ColorRGBA GHOST    = new ColorRGBA(0.910f, 0.851f, 0.627f, 0.55f); // #e8d9a0
    private static final ColorRGBA HP_TOP   = new ColorRGBA(0.753f, 0.227f, 0.169f, 1f); // #c03a2b
    private static final ColorRGBA HP_BOT   = new ColorRGBA(0.494f, 0.122f, 0.078f, 1f); // #7e1f14
    private static final ColorRGBA ST_TOP   = new ColorRGBA(0.365f, 0.608f, 0.290f, 1f); // #5d9b4a
    private static final ColorRGBA ST_BOT   = new ColorRGBA(0.235f, 0.420f, 0.180f, 1f); // #3c6b2e
    private static final ColorRGBA BOSS_TOP = new ColorRGBA(0.831f, 0.694f, 0.227f, 1f); // #d4b13a
    private static final ColorRGBA BOSS_BOT = new ColorRGBA(0.541f, 0.353f, 0.063f, 1f); // #8a5a10
    private static final ColorRGBA TXT_DIM  = new ColorRGBA(0.725f, 0.671f, 0.549f, 1f); // #b9ab8c
    private static final ColorRGBA TXT_HINT = new ColorRGBA(0.604f, 0.553f, 0.447f, 1f); // #9a8d72
    private static final ColorRGBA TXT_CTRL = new ColorRGBA(0.702f, 0.655f, 0.549f, 1f); // #b3a78c
    private static final ColorRGBA TXT_WIPE = new ColorRGBA(0.435f, 0.392f, 0.314f, 1f); // #6f6450
    private static final ColorRGBA SEP      = new ColorRGBA(0.180f, 0.149f, 0.094f, 1f); // #2e2618

    // ------------------------------------------------------------ z layers
    private static final float Z_BORDER = 1f, Z_BACK = 2f, Z_GHOST = 3f, Z_FILL = 4f, Z_TEXT = 5f;
    private static final float Z_VIG = 6f, Z_PANEL = 6.4f, Z_PANEL_BG = 6.5f, Z_PANEL_TX = 6.7f;
    private static final float Z_LOCKON = 8f, Z_DEATH = 9f, Z_MSG = 10f;
    private static final float Z_TITLE = 12f, Z_PAUSE = 12.5f;

    private final GameCtx ctx;
    private final BitmapFont font;
    private final Quad unitQuad = new Quad(1f, 1f);
    private final float W, H, u, bw;

    private final Node root;        // gameplay HUD (hidden while the title shows)
    private Node bossNode, vigNode, lockNode, levelNode, titleNode, pausedNode;

    // bars
    private Geometry hpBorder, hpBack, hpGhost, hpFill;
    private Geometry stBorder, stBack, stFill;
    private float hpLeft, hpTopY, hpHv, stTopY, stHv;
    private float hpInnerW, hpInnerH, stInnerH;
    private float hpFrac = 1f, ghostFrac = 1f, ghostDelay;
    private BitmapText flaskText;
    private int lastFlasks = Integer.MIN_VALUE, lastFlasksMax = Integer.MIN_VALUE;

    // gloria counter
    private Geometry glBorder, glBack;
    private BitmapText gloriaLabel, gloriaNum;
    private int lastGloria = Integer.MIN_VALUE;

    // prompt
    private BitmapText promptText;
    private String lastPrompt;

    // boss bar
    private BitmapText bossName;
    private Geometry bossFill;
    private float bossInnerW, bossInnerH;
    private String lastBossName;

    // center message
    private BitmapText msgText, subText;
    private final ColorRGBA msgBase = BONE.clone();
    private float msgT, msgDur;
    private boolean msgActive, msgHasSub;

    // vignette
    private final ColorRGBA vigColor = new ColorRGBA(0.55f, 0.04f, 0.04f, 0f);
    private float vigA, hurtT = 9f;
    private boolean lowHp;

    // level-up panel
    private BitmapText lvlCost;
    private final BitmapText[] rowKey = new BitmapText[3];
    private final BitmapText[] rowLabel = new BitmapText[3];
    private final BitmapText[] rowVal = new BitmapText[3];
    private int lastCost = -1, lastVig = -1, lastEnd = -1, lastStr = -1, lastAfford = -1;

    // title
    private BitmapText startText;
    private float timeAcc;

    // death fade
    private Geometry deathQuad;

    public Hud(GameCtx ctx) {
        this.ctx = ctx;
        W = ctx.cam.getWidth();
        H = ctx.cam.getHeight();
        u = W / 1280f;
        bw = Math.max(1f, u); // 1 css px border thickness

        font = ctx.assets.loadFont("Interface/Fonts/Default.fnt");

        root = new Node("hudRoot");
        ctx.guiNode.attachChild(root);

        buildBars();
        buildGloria();
        buildPrompt();
        buildBossBar();
        buildMessage();
        buildVignette();
        buildLockon();
        buildLevelPanel();
        buildTitle();
        buildPaused();
        buildDeathFade();

        // sensible defaults for base stats (vigor/endurance 8) until Main pushes real values
        setVitals(144f, 144f, 103f, 103f);
        setFlasks(4, 4);
        setGloria(0);
        showTitle(true);
    }

    // =================================================================== API

    /** Per-frame animation: ghost trail, message fade, vignette, title pulse. */
    public void update(float tpf) {
        timeAcc += tpf;

        // pale ghost trail behind the hp fill (CSS: transition 0.6s ease 0.25s)
        if (ghostFrac > hpFrac) {
            if (ghostDelay > 0f) ghostDelay -= tpf;
            else ghostFrac = Math.max(hpFrac, ghostFrac - tpf * Math.max(0.4f, (ghostFrac - hpFrac) * 2f));
        }
        hpGhost.setLocalScale(ghostFrac * hpInnerW, hpInnerH, 1f);

        // timed center message (0.7 s fade in/out, like the CSS opacity transition)
        if (msgActive) {
            msgT += tpf;
            if (msgT > msgDur + 0.7f) {
                msgActive = false;
                msgText.setCullHint(CullHint.Always);
                subText.setCullHint(CullHint.Always);
            } else {
                float a = FastMath.clamp(Math.min(msgT / 0.7f, (msgDur + 0.7f - msgT) / 0.7f), 0f, 1f);
                msgText.setColor(new ColorRGBA(msgBase.r, msgBase.g, msgBase.b, a));
                if (msgHasSub) subText.setColor(new ColorRGBA(BONE.r, BONE.g, BONE.b, a));
            }
        }

        // hurt flash (strong for 0.12 s, then 0.5 s release) + steady low-hp glow
        float hurtA = 0f;
        if (hurtT < 0.62f) {
            hurtT += tpf;
            hurtA = hurtT < 0.12f ? 0.75f : Math.max(0f, 0.75f * (1f - (hurtT - 0.12f) / 0.5f));
        }
        float target = Math.max(lowHp ? 0.45f : 0f, hurtA);
        float rate = target > vigA ? 28f : 7f;
        vigA += (target - vigA) * Math.min(1f, tpf * rate);
        vigColor.a = vigA;
        vigNode.setCullHint(vigA < 0.012f ? CullHint.Always : CullHint.Inherit);

        // pulsing "CLICCA PER INIZIARE" while the title is up (2.2 s period)
        if (titleNode.getCullHint() != CullHint.Always) {
            float a = 0.725f + 0.275f * FastMath.sin(timeAcc * FastMath.TWO_PI / 2.2f);
            startText.setColor(new ColorRGBA(BONE.r, BONE.g, BONE.b, a));
        }
    }

    /** Updates hp/stamina fills; bar widths grow with max values like the JS HUD. */
    public void setVitals(float hp, float maxHp, float st, float maxSt) {
        float w = Math.min(420f, 140f + maxHp) * u;
        float sw = Math.min(360f, 110f + maxSt) * u;
        float frac = maxHp > 0f ? FastMath.clamp(hp / maxHp, 0f, 1f) : 0f;
        float sfrac = maxSt > 0f ? FastMath.clamp(st / maxSt, 0f, 1f) : 0f;

        if (frac < hpFrac) ghostDelay = 0.25f;     // (re)arm the trail delay on damage
        if (frac > ghostFrac) ghostFrac = frac;    // heals snap the ghost up instantly
        hpFrac = frac;

        hpInnerW = w - 2f * bw;
        hpBorder.setLocalTranslation(hpLeft - bw, hpTopY - hpHv - bw, Z_BORDER);
        hpBorder.setLocalScale(w + 2f * bw, hpHv + 2f * bw, 1f);
        hpBack.setLocalTranslation(hpLeft, hpTopY - hpHv, Z_BACK);
        hpBack.setLocalScale(w, hpHv, 1f);
        hpGhost.setLocalTranslation(hpLeft + bw, hpTopY - hpHv + bw, Z_GHOST);
        hpFill.setLocalTranslation(hpLeft + bw, hpTopY - hpHv + bw, Z_FILL);
        hpFill.setLocalScale(frac * hpInnerW, hpInnerH, 1f);

        float stInnerW = sw - 2f * bw;
        stBorder.setLocalTranslation(hpLeft - bw, stTopY - stHv - bw, Z_BORDER);
        stBorder.setLocalScale(sw + 2f * bw, stHv + 2f * bw, 1f);
        stBack.setLocalTranslation(hpLeft, stTopY - stHv, Z_BACK);
        stBack.setLocalScale(sw, stHv, 1f);
        stFill.setLocalTranslation(hpLeft + bw, stTopY - stHv + bw, Z_FILL);
        stFill.setLocalScale(sfrac * stInnerW, stInnerH, 1f);
    }

    /** Flask counter under the bars ("VINVM ×N"), dimmed when empty. */
    public void setFlasks(int n, int max) {
        if (n == lastFlasks && max == lastFlasksMax) return;
        lastFlasks = n;
        lastFlasksMax = max;
        flaskText.setText("VINVM ×" + n);
        flaskText.setColor(new ColorRGBA(BONE.r, BONE.g, BONE.b, n > 0 ? 1f : 0.3f));
    }

    /** GLORIA counter, top-right. */
    public void setGloria(int n) {
        if (n == lastGloria) return;
        lastGloria = n;
        gloriaNum.setText(Integer.toString(n));
        layoutGloria();
    }

    /** Bottom-center interaction prompt; null hides it. */
    public void setPrompt(String textOrNull) {
        if (textOrNull == null) {
            if (lastPrompt != null) {
                lastPrompt = null;
                promptText.setCullHint(CullHint.Always);
            }
            return;
        }
        if (!textOrNull.equals(lastPrompt)) {
            lastPrompt = textOrNull;
            promptText.setText(latin(textOrNull));
        }
        promptText.setCullHint(CullHint.Inherit);
    }

    /**
     * Big serif-style center message with an optional sub-line, fading in and
     * out over 0.7 s, fully visible for {@code seconds}.
     */
    public void message(String text, ColorRGBA color, float seconds, String sub) {
        msgBase.set(color != null ? color : BONE);
        msgText.setText(latin(text));
        msgHasSub = sub != null && !sub.isEmpty();
        subText.setText(msgHasSub ? latin(sub) : "");
        msgDur = Math.max(0.1f, seconds);
        msgT = 0f;
        msgActive = true;
        msgText.setColor(new ColorRGBA(msgBase.r, msgBase.g, msgBase.b, 0f));
        subText.setColor(new ColorRGBA(BONE.r, BONE.g, BONE.b, 0f));
        msgText.setCullHint(CullHint.Inherit);
        subText.setCullHint(msgHasSub ? CullHint.Inherit : CullHint.Always);
    }

    /** Boss name + gold hp bar at the bottom-center; null name hides it. */
    public void bossBar(String nameOrNull, float frac) {
        if (nameOrNull == null) {
            lastBossName = null;
            bossNode.setCullHint(CullHint.Always);
            return;
        }
        bossNode.setCullHint(CullHint.Inherit);
        if (!nameOrNull.equals(lastBossName)) {
            lastBossName = nameOrNull;
            bossName.setText(latin(nameOrNull));
        }
        bossFill.setLocalScale(FastMath.clamp(frac, 0f, 1f) * bossInnerW, bossInnerH, 1f);
    }

    /** Short red screen-edge flash on taking damage. */
    public void hurtFlash() {
        hurtT = 0f;
    }

    /** Steady red screen-edge glow while hp is critical. */
    public void lowHpVignette(boolean on) {
        lowHp = on;
    }

    /**
     * Positions the gold lock-on diamond over a world position (projected via
     * the camera). Pass null — or a point behind the camera — to hide it.
     */
    public void lockonAt(Vector3f worldPosOrNull) {
        if (worldPosOrNull == null) {
            lockNode.setCullHint(CullHint.Always);
            return;
        }
        Vector3f scr = ctx.cam.getScreenCoordinates(worldPosOrNull);
        if (scr.z >= 1f) { // behind the camera
            lockNode.setCullHint(CullHint.Always);
            return;
        }
        lockNode.setCullHint(CullHint.Inherit);
        lockNode.setLocalTranslation(scr.x, scr.y, Z_LOCKON);
    }

    /** Shrine level-up panel (Sacrarium): stats, cost, greyed rows when broke. */
    public void levelPanel(boolean show, int vig, int end, int str, int cost, boolean canAfford) {
        levelNode.setCullHint(show ? CullHint.Inherit : CullHint.Always);
        if (!show) return;
        if (cost != lastCost) {
            lastCost = cost;
            lvlCost.setText("Prossimo livello: " + cost + " gloria");
        }
        if (vig != lastVig) { lastVig = vig; rowVal[0].setText(Integer.toString(vig)); }
        if (end != lastEnd) { lastEnd = end; rowVal[1].setText(Integer.toString(end)); }
        if (str != lastStr) { lastStr = str; rowVal[2].setText(Integer.toString(str)); }
        int aff = canAfford ? 1 : 0;
        if (aff != lastAfford) {
            lastAfford = aff;
            float a = canAfford ? 1f : 0.4f;
            for (int i = 0; i < 3; i++) {
                rowKey[i].setColor(new ColorRGBA(GOLD.r, GOLD.g, GOLD.b, a));
                rowLabel[i].setColor(new ColorRGBA(BONE.r, BONE.g, BONE.b, a));
                rowVal[i].setColor(new ColorRGBA(BONE.r, BONE.g, BONE.b, a));
            }
        }
    }

    /** Shows/hides the title screen; the gameplay HUD is hidden while it is up. */
    public void showTitle(boolean on) {
        titleNode.setCullHint(on ? CullHint.Inherit : CullHint.Always);
        root.setCullHint(on ? CullHint.Always : CullHint.Inherit);
    }

    /** Pause overlay. */
    public void showPaused(boolean on) {
        pausedNode.setCullHint(on ? CullHint.Inherit : CullHint.Always);
    }

    /** Full-screen dark-red death fade, k in 0..1 (0 hides it). */
    public void showDeathFade(float k) {
        float a = FastMath.clamp(k, 0f, 1f) * 0.96f;
        if (a <= 0.003f) {
            deathQuad.setCullHint(CullHint.Always);
            return;
        }
        deathQuad.setCullHint(CullHint.Inherit);
        deathQuad.getMaterial().setColor("Color", new ColorRGBA(0.13f, 0.012f, 0.012f, a));
    }

    // ============================================================ builders

    private void buildBars() {
        hpLeft = 24f * u;
        hpTopY = H - 22f * u;
        hpHv = 14f * u;
        stTopY = hpTopY - hpHv - 7f * u;
        stHv = 10f * u;
        hpInnerH = hpHv - 2f * bw;
        stInnerH = stHv - 2f * bw;

        hpBorder = solid("hpBorder", BORDER, Z_BORDER, root);
        hpBack = solid("hpBack", INK, Z_BACK, root);
        hpGhost = solid("hpGhost", GHOST, Z_GHOST, root);
        hpFill = gradient("hpFill", HP_BOT, HP_BOT, HP_TOP, HP_TOP, Z_FILL, root);

        stBorder = solid("stBorder", BORDER, Z_BORDER, root);
        stBack = solid("stBack", INK, Z_BACK, root);
        stFill = gradient("stFill", ST_BOT, ST_BOT, ST_TOP, ST_TOP, Z_FILL, root);

        flaskText = text(null, 19f * u, BONE, Z_TEXT, root);
        flaskText.setLocalTranslation(hpLeft, stTopY - stHv - 6f * u, Z_TEXT);
    }

    private void buildGloria() {
        glBorder = solid("gloriaBorder", BORDER, Z_BORDER, root);
        glBack = solid("gloriaBack", INK, Z_BACK, root);
        gloriaLabel = text("GLORIA", 13f * u, BONE, Z_TEXT, root);
        gloriaNum = text("0", 22f * u, GOLD, Z_TEXT, root);
    }

    private void layoutGloria() {
        float numW = gloriaNum.getLineWidth();
        float labelW = gloriaLabel.getLineWidth();
        float padX = 16f * u, padY = 6f * u, gap = 10f * u;
        float boxW = padX * 2f + labelW + gap + numW;
        float boxH = padY * 2f + 24f * u;
        float bx = W - 28f * u - boxW;
        float bTop = H - 22f * u;
        glBorder.setLocalTranslation(bx - bw, bTop - boxH - bw, Z_BORDER);
        glBorder.setLocalScale(boxW + 2f * bw, boxH + 2f * bw, 1f);
        glBack.setLocalTranslation(bx, bTop - boxH, Z_BACK);
        glBack.setLocalScale(boxW, boxH, 1f);
        gloriaLabel.setLocalTranslation(bx + padX, bTop - padY - 9f * u, Z_TEXT);
        gloriaNum.setLocalTranslation(bx + padX + labelW + gap, bTop - padY, Z_TEXT);
    }

    private void buildPrompt() {
        promptText = centered("", 20f * u, BONE, 0.17f * H + 24f * u, Z_TEXT, root);
        promptText.setCullHint(CullHint.Always);
    }

    private void buildBossBar() {
        bossNode = new Node("bossbar");
        root.attachChild(bossNode);

        float barW = Math.min(720f * u, 0.7f * W);
        float barH = 12f * u;
        float bx = (W - barW) * 0.5f;
        float by = 0.07f * H; // bar bottom edge
        bossInnerW = barW - 2f * bw;
        bossInnerH = barH - 2f * bw;

        Geometry border = solid("bossBorder", BORDER, Z_BORDER, bossNode);
        border.setLocalTranslation(bx - bw, by - bw, Z_BORDER);
        border.setLocalScale(barW + 2f * bw, barH + 2f * bw, 1f);
        Geometry back = solid("bossBack", INK, Z_BACK, bossNode);
        back.setLocalTranslation(bx, by, Z_BACK);
        back.setLocalScale(barW, barH, 1f);
        bossFill = gradient("bossFill", BOSS_BOT, BOSS_BOT, BOSS_TOP, BOSS_TOP, Z_FILL, bossNode);
        bossFill.setLocalTranslation(bx + bw, by + bw, Z_FILL);
        bossFill.setLocalScale(bossInnerW, bossInnerH, 1f);

        bossName = centered("", 21f * u, BONE, by + barH + 5f * u + 26f * u, Z_TEXT, bossNode);
        bossNode.setCullHint(CullHint.Always);
    }

    private void buildMessage() {
        msgText = centered("", 64f * u, BONE, 0.62f * H, Z_MSG, root);
        subText = centered("", 20f * u, BONE, 0.50f * H, Z_MSG, root);
        msgText.setCullHint(CullHint.Always);
        subText.setCullHint(CullHint.Always);
    }

    private void buildVignette() {
        vigNode = new Node("vignette");
        vigNode.setLocalTranslation(0f, 0f, Z_VIG);
        root.attachChild(vigNode);

        Material vm = new Material(ctx.assets, "Common/MatDefs/Misc/Unshaded.j3md");
        vm.setBoolean("VertexColor", true);
        vm.setColor("Color", vigColor); // mutated per frame (alpha)
        vm.getAdditionalRenderState().setBlendMode(BlendMode.Alpha);

        float tv = 0.24f * H; // top/bottom band thickness
        float ts = 0.20f * W; // side band thickness
        ColorRGBA in = new ColorRGBA(1f, 1f, 1f, 0f);
        ColorRGBA out = new ColorRGBA(1f, 1f, 1f, 1f);

        band(vm, "vigBottom", 0f, 0f, W, tv, out, out, in, in);
        band(vm, "vigTop", 0f, H - tv, W, tv, in, in, out, out);
        band(vm, "vigLeft", 0f, 0f, ts, H, out, in, in, out);
        band(vm, "vigRight", W - ts, 0f, ts, H, in, out, out, in);
        vigNode.setCullHint(CullHint.Always);
    }

    private void band(Material mat, String name, float x, float y, float w, float h,
                      ColorRGBA bl, ColorRGBA br, ColorRGBA tr, ColorRGBA tl) {
        Geometry g = new Geometry(name, gradMesh(bl, br, tr, tl));
        g.setMaterial(mat);
        g.setLocalTranslation(x, y, 0f);
        g.setLocalScale(w, h, 1f);
        vigNode.attachChild(g);
    }

    private void buildLockon() {
        lockNode = new Node("lockon");
        root.attachChild(lockNode);
        float r = 13f * u;
        float th = Math.max(2f, 2f * u);
        ColorRGBA c = new ColorRGBA(GOLD.r, GOLD.g, GOLD.b, 0.92f);
        Geometry bottom = solid("lockB", c, 0f, lockNode);
        bottom.setLocalTranslation(-r, -r, 0f);
        bottom.setLocalScale(2f * r, th, 1f);
        Geometry top = solid("lockT", c, 0f, lockNode);
        top.setLocalTranslation(-r, r - th, 0f);
        top.setLocalScale(2f * r, th, 1f);
        Geometry left = solid("lockL", c, 0f, lockNode);
        left.setLocalTranslation(-r, -r, 0f);
        left.setLocalScale(th, 2f * r, 1f);
        Geometry right = solid("lockR", c, 0f, lockNode);
        right.setLocalTranslation(r - th, -r, 0f);
        right.setLocalScale(th, 2f * r, 1f);
        lockNode.rotate(0f, 0f, FastMath.QUARTER_PI); // square -> diamond
        lockNode.setLocalTranslation(W * 0.5f, H * 0.5f, Z_LOCKON);
        lockNode.setCullHint(CullHint.Always);
    }

    private void buildLevelPanel() {
        levelNode = new Node("levelup");
        root.attachChild(levelNode);

        float pw = 340f * u, ph = 250f * u;
        float px = W - 30f * u - pw;
        float pTop = H * 0.5f + ph * 0.5f;
        float padX = 28f * u;
        float rowW = pw - 2f * padX;

        Geometry border = solid("lvlBorder", GOLD_DIM, Z_PANEL, levelNode);
        border.setLocalTranslation(px - bw, pTop - ph - bw, Z_PANEL);
        border.setLocalScale(pw + 2f * bw, ph + 2f * bw, 1f);
        Geometry back = solid("lvlBack", new ColorRGBA(0.031f, 0.024f, 0.016f, 0.9f), Z_PANEL_BG, levelNode);
        back.setLocalTranslation(px, pTop - ph, Z_PANEL_BG);
        back.setLocalScale(pw, ph, 1f);

        BitmapText title = boxed("Sacrarium", 22f * u, GOLD, px, pTop - 16f * u, pw,
                BitmapFont.Align.Center, Z_PANEL_TX, levelNode);
        title.setCullHint(CullHint.Inherit);
        lvlCost = boxed("Prossimo livello: 0 gloria", 14f * u, TXT_DIM, px, pTop - 48f * u, pw,
                BitmapFont.Align.Center, Z_PANEL_TX, levelNode);

        String[] labels = {"Vigor - salute", "Industria - stamina", "Virtus - danno"};
        for (int i = 0; i < 3; i++) {
            float rowTop = pTop - 78f * u - i * 36f * u;
            Geometry sep = solid("lvlSep" + i, SEP, Z_PANEL_TX, levelNode);
            sep.setLocalTranslation(px + padX, rowTop + 8f * u, Z_PANEL_TX);
            sep.setLocalScale(rowW, Math.max(1f, u), 1f);

            rowKey[i] = text("[" + (i + 1) + "]", 17f * u, GOLD, Z_PANEL_TX, levelNode);
            rowKey[i].setLocalTranslation(px + padX, rowTop, Z_PANEL_TX);
            rowLabel[i] = text(labels[i], 17f * u, BONE, Z_PANEL_TX, levelNode);
            rowLabel[i].setLocalTranslation(px + padX + 36f * u, rowTop, Z_PANEL_TX);
            rowVal[i] = boxed("0", 17f * u, BONE, px + padX, rowTop, rowW,
                    BitmapFont.Align.Right, Z_PANEL_TX, levelNode);
        }

        boxed("[E] per alzarsi e tornare a combattere", 13f * u, TXT_HINT,
                px, pTop - ph + 34f * u, pw, BitmapFont.Align.Center, Z_PANEL_TX, levelNode);

        levelNode.setCullHint(CullHint.Always);
    }

    private void buildTitle() {
        titleNode = new Node("title");
        titleNode.setLocalTranslation(0f, 0f, Z_TITLE);
        ctx.guiNode.attachChild(titleNode);

        // radial-ish backdrop: darker towards top/bottom edges
        ColorRGBA center = new ColorRGBA(0.071f, 0.047f, 0.031f, 0.90f);
        ColorRGBA edge = new ColorRGBA(0.020f, 0.012f, 0.008f, 0.97f);
        Geometry bgLo = gradient("titleBgLo", edge, edge, center, center, 0f, titleNode);
        bgLo.setLocalScale(W, H * 0.5f, 1f);
        Geometry bgHi = gradient("titleBgHi", center, center, edge, edge, 0f, titleNode);
        bgHi.setLocalTranslation(0f, H * 0.5f, 0f);
        bgHi.setLocalScale(W, H * 0.5f, 1f);

        centered("I M P E R I V M\nA E T E R N V M", 88f * u, GOLD, 0.94f * H, 0.5f, titleNode);

        // thin gold rule (transparent -> gold-dim -> transparent)
        float ruleW = 420f * u, ruleH = Math.max(1f, 1.4f * u);
        float ruleY = 0.635f * H;
        ColorRGBA g0 = new ColorRGBA(GOLD_DIM.r, GOLD_DIM.g, GOLD_DIM.b, 0f);
        Geometry rl = gradient("ruleL", g0, GOLD_DIM, GOLD_DIM, g0, 0.5f, titleNode);
        rl.setLocalTranslation((W - ruleW) * 0.5f, ruleY, 0.5f);
        rl.setLocalScale(ruleW * 0.5f, ruleH, 1f);
        Geometry rr = gradient("ruleR", GOLD_DIM, g0, g0, GOLD_DIM, 0.5f, titleNode);
        rr.setLocalTranslation(W * 0.5f, ruleY, 0.5f);
        rr.setLocalScale(ruleW * 0.5f, ruleH, 1f);

        centered("l'impero è caduto - la sua ombra ti attende", 19f * u, BONE, 0.60f * H, 0.5f, titleNode);

        // two-column controls list (gold key, dim description)
        String[] k1 = {"W A S D", "Mouse", "Shift", "Spazio", "Click sin."};
        String[] d1 = {"movimento", "telecamera", "corsa", "schivata", "attacco leggero"};
        String[] k2 = {"Click des.", "Q", "F", "E", "Esc"};
        String[] d2 = {"attacco pesante", "aggancia bersaglio", "bevi il vinum sacrum",
                "interagisci / riposa", "pausa"};
        float fs = 15f * u, rowStep = 27f * u, keyW = 96f * u;
        float c1x = W * 0.5f - 300f * u;
        float c2x = W * 0.5f + 36f * u;
        for (int i = 0; i < 5; i++) {
            float y = 0.52f * H - i * rowStep;
            text(k1[i], fs, GOLD, 0.5f, titleNode).setLocalTranslation(c1x, y, 0.5f);
            text(d1[i], fs, TXT_CTRL, 0.5f, titleNode).setLocalTranslation(c1x + keyW, y, 0.5f);
            text(k2[i], fs, GOLD, 0.5f, titleNode).setLocalTranslation(c2x, y, 0.5f);
            text(d2[i], fs, TXT_CTRL, 0.5f, titleNode).setLocalTranslation(c2x + keyW, y, 0.5f);
        }

        startText = centered("- CLICCA PER INIZIARE -", 21f * u, BONE, 0.205f * H, 0.5f, titleNode);
        centered("premi [N] qui nel titolo per cancellare il salvataggio", 12f * u, TXT_WIPE,
                0.135f * H, 0.5f, titleNode);
    }

    private void buildPaused() {
        pausedNode = new Node("paused");
        pausedNode.setLocalTranslation(0f, 0f, Z_PAUSE);
        ctx.guiNode.attachChild(pausedNode);

        Geometry bg = solid("pausedBg", new ColorRGBA(0.020f, 0.012f, 0.008f, 0.9f), 0f, pausedNode);
        bg.setLocalScale(W, H, 1f);
        centered("PAUSA", 42f * u, BONE, 0.58f * H, 0.5f, pausedNode);
        centered("clicca per riprendere il cammino", 16f * u, TXT_HINT, 0.47f * H, 0.5f, pausedNode);
        pausedNode.setCullHint(CullHint.Always);
    }

    private void buildDeathFade() {
        deathQuad = solid("deathFade", new ColorRGBA(0.13f, 0.012f, 0.012f, 0f), Z_DEATH, ctx.guiNode);
        deathQuad.setLocalScale(W, H, 1f);
        deathQuad.setCullHint(CullHint.Always);
    }

    // ============================================================= helpers

    /** Unit quad with a flat alpha-blended color; caller positions/scales it. */
    private Geometry solid(String name, ColorRGBA c, float z, Node parent) {
        Geometry g = new Geometry(name, unitQuad);
        Material m = new Material(ctx.assets, "Common/MatDefs/Misc/Unshaded.j3md");
        m.setColor("Color", c.clone());
        m.getAdditionalRenderState().setBlendMode(BlendMode.Alpha);
        g.setMaterial(m);
        g.setLocalTranslation(0f, 0f, z);
        parent.attachChild(g);
        return g;
    }

    /** Unit quad with per-corner vertex colors (vertical/horizontal gradients). */
    private Geometry gradient(String name, ColorRGBA bl, ColorRGBA br, ColorRGBA tr, ColorRGBA tl,
                              float z, Node parent) {
        Geometry g = new Geometry(name, gradMesh(bl, br, tr, tl));
        Material m = new Material(ctx.assets, "Common/MatDefs/Misc/Unshaded.j3md");
        m.setBoolean("VertexColor", true);
        m.getAdditionalRenderState().setBlendMode(BlendMode.Alpha);
        g.setMaterial(m);
        g.setLocalTranslation(0f, 0f, z);
        parent.attachChild(g);
        return g;
    }

    private static Mesh gradMesh(ColorRGBA bl, ColorRGBA br, ColorRGBA tr, ColorRGBA tl) {
        Mesh m = new Mesh();
        m.setBuffer(Type.Position, 3, new float[]{0, 0, 0, 1, 0, 0, 1, 1, 0, 0, 1, 0});
        m.setBuffer(Type.TexCoord, 2, new float[]{0, 0, 1, 0, 1, 1, 0, 1});
        m.setBuffer(Type.Color, 4, new float[]{
                bl.r, bl.g, bl.b, bl.a,
                br.r, br.g, br.b, br.a,
                tr.r, tr.g, tr.b, tr.a,
                tl.r, tl.g, tl.b, tl.a});
        m.setBuffer(Type.Index, 3, new int[]{0, 1, 2, 0, 2, 3});
        m.updateBound();
        return m;
    }

    private BitmapText text(String s, float size, ColorRGBA c, float z, Node parent) {
        BitmapText t = new BitmapText(font);
        t.setSize(size);
        t.setColor(c.clone());
        if (s != null) t.setText(s);
        t.setLocalTranslation(0f, 0f, z);
        parent.attachChild(t);
        return t;
    }

    /** Full-screen-width horizontally centered text; y is the line top. */
    private BitmapText centered(String s, float size, ColorRGBA c, float yTop, float z, Node parent) {
        return boxed(s, size, c, 0f, yTop, W, BitmapFont.Align.Center, z, parent);
    }

    /** Text aligned inside a box of width boxW whose top-left is (x, yTop). */
    private BitmapText boxed(String s, float size, ColorRGBA c, float x, float yTop, float boxW,
                             BitmapFont.Align align, float z, Node parent) {
        BitmapText t = text(s, size, c, z, parent);
        t.setBox(new Rectangle(0f, 0f, boxW, size * 3f));
        t.setAlignment(align);
        t.setLocalTranslation(x, yTop, z);
        return t;
    }

    /**
     * The default jME font only covers Latin-1: swap typographic punctuation
     * (em dashes, curly quotes) and strip the {@code <b>} markup the JS HUD
     * embedded in prompt strings.
     */
    private static String latin(String s) {
        if (s == null) return "";
        return s.replace("<b>", "").replace("</b>", "")
                .replace('—', '-').replace('–', '-')
                .replace('’', '\'').replace('‘', '\'');
    }
}
