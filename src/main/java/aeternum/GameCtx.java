package aeternum;

import com.jme3.asset.AssetManager;
import com.jme3.input.InputManager;
import com.jme3.renderer.Camera;
import com.jme3.scene.Node;

/**
 * Shared context handed to every subsystem. Fields are assigned once by Main
 * during startup, in this order: app/assets/nodes/cam/input/tex, then world,
 * audio, fx, hud, rigs, player, enemies, boss, game.
 */
public class GameCtx {
    public Main app;
    public AssetManager assets;
    public Node rootNode;
    public Node guiNode;
    public Camera cam;
    public InputManager input;

    public TexLib tex;
    public World world;
    public PlayerCtrl player;
    public EnemyManager enemies;
    public Boss boss;
    public Hud hud;
    public SynthAudio audio;
    public Fx fx;
    public GameState game;

    /** Seconds since app start (updated by Main each frame). */
    public float time;
}
