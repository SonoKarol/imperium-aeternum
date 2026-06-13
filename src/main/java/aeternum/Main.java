package aeternum;

import com.jme3.app.SimpleApplication;
import com.jme3.app.state.ScreenshotAppState;
import com.jme3.environment.EnvironmentCamera;
import com.jme3.environment.LightProbeFactory;
import com.jme3.environment.generation.JobProgressAdapter;
import com.jme3.light.AmbientLight;
import com.jme3.light.LightProbe;
import com.jme3.math.ColorRGBA;
import com.jme3.math.FastMath;
import com.jme3.math.Vector3f;
import com.jme3.post.FilterPostProcessor;
import com.jme3.post.filters.BloomFilter;
import com.jme3.post.filters.FXAAFilter;
import com.jme3.post.filters.LightScatteringFilter;
import com.jme3.post.filters.ToneMapFilter;
import com.jme3.post.ssao.SSAOFilter;
import com.jme3.shadow.DirectionalLightShadowFilter;
import com.jme3.shadow.EdgeFilteringMode;
import com.jme3.system.AppSettings;
import com.jme3.texture.Texture;
import com.jme3.texture.plugins.HDRLoader;
import com.jme3.util.SkyFactory;
import com.jme3.water.WaterFilter;

/**
 * IMPERIVM AETERNVM — a souls-like set in the ashes of the Roman Empire.
 * Java/jMonkeyEngine edition with a realistic rendering pipeline:
 * HDRI sky + IBL light probe, PBR materials, cascaded shadows, SSAO,
 * bloom, god rays, reflective water and filmic tone mapping.
 */
public class Main extends SimpleApplication {

    static boolean shotMode;

    private final GameCtx ctx = new GameCtx();
    private EnvironmentCamera envCam;
    private AmbientLight fallbackAmbient;
    private boolean probeRequested;
    private boolean probeReady;

    private ScreenshotAppState shots;
    private int shotStage;
    private float shotT;

    public static void main(String[] args) {
        for (String a : args) {
            if (a.equals("--shot")) shotMode = true;
        }
        Main app = new Main();
        AppSettings s = new AppSettings(true);
        s.setTitle("IMPERIVM AETERNVM");
        s.setResolution(1600, 900);
        s.setGammaCorrection(true);
        s.setSamples(4);
        s.setVSync(true);
        app.setSettings(s);
        app.setShowSettings(false);
        app.setPauseOnLostFocus(false);
        app.start();
    }

    @Override
    public void simpleInitApp() {
        assetManager.registerLoader(HDRLoader.class, "hdr");
        inputManager.deleteMapping(INPUT_MAPPING_EXIT); // ESC is pause, not quit
        flyCam.setEnabled(false);
        setDisplayFps(false);
        setDisplayStatView(false);

        // ---- shared context ----
        ctx.app = this;
        ctx.assets = assetManager;
        ctx.rootNode = rootNode;
        ctx.guiNode = guiNode;
        ctx.cam = cam;
        ctx.input = inputManager;
        ctx.tex = new TexLib(assetManager);

        // ---- sky (sunset HDRI, also feeds the IBL probe) ----
        Texture skyTex = assetManager.loadTexture(
                new com.jme3.asset.TextureKey("Textures/sky.hdr", true));
        com.jme3.scene.Spatial sky = SkyFactory.createSky(
                assetManager, skyTex, SkyFactory.EnvMapType.EquirectMap);
        sky.rotate(0f, 5.03f, 0f); // put the burning horizon to the north, behind the colosseum
        rootNode.attachChild(sky);

        // ---- subsystems (order matters: World's braziers need Fx) ----
        ctx.audio = new SynthAudio();
        ctx.fx = new Fx(ctx);
        ctx.world = new World(ctx);
        ctx.hud = new Hud(ctx);
        ctx.player = new PlayerCtrl(ctx);
        ctx.enemies = new EnemyManager(ctx);
        ctx.boss = new Boss(ctx);
        ctx.game = new GameState(ctx);
        Save.load(ctx.player, ctx.boss);

        // until the light probe is baked, a soft ambient keeps PBR visible
        fallbackAmbient = new AmbientLight(new ColorRGBA(0.5f, 0.42f, 0.45f, 1f).mult(0.9f));
        rootNode.addLight(fallbackAmbient);

        envCam = new EnvironmentCamera(256, new Vector3f(0f, 12f, -20f));
        stateManager.attach(envCam);

        buildFilters();

        shots = new ScreenshotAppState("shots/", "java");
        stateManager.attach(shots);
    }

    private void buildFilters() {
        FilterPostProcessor fpp = new FilterPostProcessor(assetManager);
        fpp.setNumSamples(4);

        DirectionalLightShadowFilter shadow =
                new DirectionalLightShadowFilter(assetManager, 4096, 4);
        shadow.setLight(ctx.world.sun);
        shadow.setShadowIntensity(0.55f);
        shadow.setEdgeFilteringMode(EdgeFilteringMode.PCFPOISSON);
        shadow.setLambda(0.65f);
        shadow.setShadowZExtend(160f);
        fpp.addFilter(shadow);

        SSAOFilter ssao = new SSAOFilter(1.8f, 1.6f, 0.33f, 0.05f);
        fpp.addFilter(ssao);

        Vector3f sunDir = ctx.world.sun.getDirection();
        WaterFilter water = new WaterFilter(rootNode, sunDir);
        water.setWaterHeight(C.WATER_LEVEL);
        water.setCenter(new Vector3f(C.LAKE_X, C.WATER_LEVEL, C.LAKE_Z));
        water.setRadius(C.LAKE_R);
        water.setShapeType(WaterFilter.AreaShape.Circular);
        water.setWaveScale(0.004f);
        water.setMaxAmplitude(0.3f);
        water.setSpeed(0.6f);
        water.setWaterColor(new ColorRGBA(0.14f, 0.10f, 0.12f, 1f));
        water.setDeepWaterColor(new ColorRGBA(0.09f, 0.06f, 0.10f, 1f));
        water.setWaterTransparency(0.12f);
        fpp.addFilter(water);

        LightScatteringFilter rays = new LightScatteringFilter(sunDir.mult(-2500f));
        rays.setLightDensity(1.1f);
        fpp.addFilter(rays);

        BloomFilter bloom = new BloomFilter(BloomFilter.GlowMode.Scene);
        bloom.setExposurePower(55f);
        bloom.setBloomIntensity(1.1f);
        fpp.addFilter(bloom);

        fpp.addFilter(new ToneMapFilter(Vector3f.UNIT_XYZ.mult(7.0f)));
        fpp.addFilter(new FXAAFilter());

        viewPort.addProcessor(fpp);
    }

    @Override
    public void simpleUpdate(float tpf) {
        ctx.time += tpf;

        // bake the IBL probe once the environment camera is ready
        if (!probeRequested && envCam.isInitialized()) {
            probeRequested = true;
            LightProbeFactory.makeProbe(envCam, rootNode, new JobProgressAdapter<LightProbe>() {
                @Override
                public void done(LightProbe probe) {
                    enqueue(() -> {
                        probe.getArea().setRadius(300f);
                        rootNode.addLight(probe);
                        fallbackAmbient.setColor(new ColorRGBA(0.5f, 0.44f, 0.48f, 1f));
                        probeReady = true;
                    });
                }
            });
        }

        boolean running = ctx.game.phase == GameState.Phase.PLAYING && !ctx.game.paused;
        if (running) {
            ctx.player.targets = new java.util.ArrayList<>(ctx.enemies.aliveList());
            if (ctx.boss.isActive()) ctx.player.targets.add(ctx.boss);
            ctx.player.update(tpf);
            ctx.enemies.update(tpf);
            ctx.boss.update(tpf);
        }
        ctx.game.update(tpf, ctx);
        if (ctx.game.phase == GameState.Phase.PLAYING) {
            ctx.player.updateCamera(tpf);
        }
        ctx.fx.update(tpf);
        ctx.world.update(tpf);
        ctx.hud.update(tpf);
        ctx.player.clearEdges();

        if (shotMode) updateShotDirector(tpf);
    }

    /** --shot: stages two scenes, saves screenshots into shots/, exits. */
    private void updateShotDirector(float tpf) {
        shotT += tpf;
        switch (shotStage) {
            case 0 -> {
                if (probeReady && ctx.time > 1.5f) {
                    ctx.game.startGame();
                    ctx.player.maxHp = 99999f;
                    ctx.player.hp = 99999f;
                    ctx.player.position.set(0.5f, 0f, -14f);
                    ctx.player.yaw = FastMath.PI;     // facing north, towards the arena
                    ctx.player.camYaw = 0f;           // camera south of the player, looking north
                    ctx.player.camPitch = 0.12f;
                    // warm up the smoothed camera so the first shot isn't mid-lerp
                    for (int i = 0; i < 60; i++) ctx.player.updateCamera(0.05f);
                    ctx.hud.message(" ", ColorRGBA.White, 0.01f, "");
                    shotStage = 1;
                    shotT = 0f;
                }
            }
            case 1 -> {
                if (shotT > 0.8f) {
                    shots.takeScreenshot();
                    shotStage = 2;
                    shotT = 0f;
                }
            }
            case 2 -> {
                if (shotT > 0.3f) {
                    ctx.player.position.set(0f, 0f, -70f);
                    ctx.player.yaw = FastMath.PI;
                    ctx.player.camYaw = 0f;
                    ctx.player.camPitch = 0.14f;
                    shotStage = 3;
                    shotT = 0f;
                }
            }
            case 3 -> {
                if (shotT > 1.1f) {
                    ctx.hud.message(" ", ColorRGBA.White, 0.01f, "");
                }
                if (shotT > 1.3f) {
                    shots.takeScreenshot();
                    shotStage = 4;
                    shotT = 0f;
                }
            }
            case 4 -> {
                if (shotT > 0.3f) {
                    // close-up of the player rig for visual debugging
                    ctx.player.position.set(0f, 0f, -3f);
                    ctx.player.yaw = 2.4f;
                    ctx.hud.message(" ", ColorRGBA.White, 0.01f, "");
                    shotStage = 5;
                    shotT = 0f;
                }
            }
            case 5, 6 -> {
                // frontal three-quarter close-up
                Vector3f p = ctx.player.position;
                Vector3f front = new Vector3f(FastMath.sin(ctx.player.yaw), 0f,
                        FastMath.cos(ctx.player.yaw)).multLocal(2.6f);
                cam.setLocation(p.add(front.x + 0.8f, 1.5f, front.z + 0.8f));
                cam.lookAt(p.add(0f, 1.0f, 0f), Vector3f.UNIT_Y);
                if (shotStage == 5 && shotT > 0.5f) {
                    shots.takeScreenshot();
                    shotStage = 6;
                    shotT = 0f;
                } else if (shotStage == 6 && shotT > 0.3f) {
                    shotStage = 8;
                    shotT = 0f;
                }
            }
            case 8, 9 -> {
                // the lake with the temple across the water
                cam.setLocation(new Vector3f(C.LAKE_X - 6f, 1.8f, C.LAKE_Z + 24f));
                cam.lookAt(new Vector3f(-38f, 4f, -30f), Vector3f.UNIT_Y);
                if (shotStage == 8 && shotT > 0.5f) {
                    shots.takeScreenshot();
                    shotStage = 9;
                    shotT = 0f;
                } else if (shotStage == 9 && shotT > 0.4f) {
                    stop();
                }
            }
        }
    }

    @Override
    public void destroy() {
        if (ctx.audio != null) ctx.audio.shutdown();
        super.destroy();
    }
}
