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
    /** Wall-clock of the last entity attack (GUI-impossible-while-attacking). */
    public volatile long lastAttackNanos = Long.MIN_VALUE / 2;

    // ── Mace smash: the SERVER-computed damage this player just dealt with a
    //    mace, plus its receive instant. Stamped from EntityDamageByEntityEvent
    //    (server-authoritative — includes the smash bonus). MaceCheck compares
    //    this against whether a genuine fall actually happened.
    public volatile long   lastMaceHitNanos = Long.MIN_VALUE / 2;
    public volatile double lastMaceDamage;
    public volatile float  lastMaceFallDistance;
    public volatile boolean lastMaceOnGround;
}
