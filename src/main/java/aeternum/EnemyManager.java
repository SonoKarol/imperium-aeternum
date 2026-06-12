package aeternum;

import com.jme3.math.Vector3f;

import java.util.ArrayList;
import java.util.List;

/**
 * Owns every {@link Enemy} in the world and the gloria they drop. Port of the
 * EnemyManager in {@code web/src/enemies.js}: same nine spawn definitions,
 * full reset on shrine rest, and a pending-gloria pool that Main drains via
 * {@link #collectGloria()}.
 */
public class EnemyManager {

    /** All enemies, dead or alive, in spawn order. */
    public final List<Enemy> list = new ArrayList<>();

    private int pendingGloria;

    /** Spawns the nine enemies exactly as enemies.js does. */
    public EnemyManager(GameCtx ctx) {
        // (type, x, z, patrolR) — along the Via Sacra
        spawn(ctx, Enemy.Type.LEGIONARIUS, -3, -6, 5);
        spawn(ctx, Enemy.Type.LEGIONARIUS, 4, -22, 6);
        spawn(ctx, Enemy.Type.LEGIONARIUS, -4, -38, 6);
        // eastern forum
        spawn(ctx, Enemy.Type.LEGIONARIUS, 30, -20, 7);
        spawn(ctx, Enemy.Type.LEGIONARIUS, 38, -26, 7);
        spawn(ctx, Enemy.Type.PRAETORIANUS, 34, -22, 5);
        // western temple guardian
        spawn(ctx, Enemy.Type.PRAETORIANUS, -38, -22, 6);
        // colosseum gate watch
        spawn(ctx, Enemy.Type.LEGIONARIUS, -5, -52, 5);
        spawn(ctx, Enemy.Type.LEGIONARIUS, 6, -54, 5);
    }

    private void spawn(GameCtx ctx, Enemy.Type type, float x, float z, float patrolR) {
        list.add(new Enemy(ctx, type, new Vector3f(x, 0, z), patrolR));
    }

    /** Respawns every enemy at full health (shrine rest / player respawn). */
    public void resetAll() {
        for (Enemy e : list) e.reset();
    }

    /** Ticks every enemy and banks gloria from any that died this frame. */
    public void update(float tpf) {
        for (Enemy e : list) {
            e.update(tpf);
            if (e.justDied) {
                e.justDied = false;
                pendingGloria += e.gloriaReward();
            }
        }
    }

    /** Returns (and clears) gloria awarded by kills since the last call. */
    public int collectGloria() {
        int g = pendingGloria;
        pendingGloria = 0;
        return g;
    }

    /** Enemies the player can currently target / hit. */
    public List<Combatant> aliveList() {
        List<Combatant> out = new ArrayList<>();
        for (Enemy e : list) {
            if (e.isAlive()) out.add(e);
        }
        return out;
    }
}
