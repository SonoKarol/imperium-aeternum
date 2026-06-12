package aeternum;

import com.jme3.math.Vector3f;

/** Anything that can be targeted and damaged (player, enemies, boss). */
public interface Combatant {
    /** World position (feet). The returned vector must not be mutated by callers. */
    Vector3f pos();

    boolean isAlive();

    /** Horizontal collision/hit radius. */
    float radius();

    /** Apply damage. poiseDmg accumulates towards a stagger. */
    void takeDamage(int amount, int poiseDmg, Vector3f from);
}
