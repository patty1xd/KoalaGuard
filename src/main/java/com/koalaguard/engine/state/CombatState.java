package com.koalaguard.engine.state;

import org.bukkit.util.Vector;

import java.util.UUID;

/**
 * Continuously-updated combat sub-state. No timing thresholds live here — only
 * the reconstructed facts checks need: which entity was last attacked, on
 * which movement tick, and the last server-applied knockback the player must
 * physically obey next.
 */
public final class CombatState {

    public UUID lastAttackTarget;
    public int  lastAttackEntityId = -1;
    public long lastAttackTick = -1;
    public long lastSwingTick = -1;
    public long lastAttackProcessedTick = -1;

    // Knockback the server told the client to take. The simulator seeds the
    // next predicted velocity with this; VelocityCheck verifies it was obeyed.
    public Vector pendingKnockback;
    public long   knockbackTick = -1;
    public boolean knockbackConsumed;

    public long lastDamageTakenTick = -1;
}
