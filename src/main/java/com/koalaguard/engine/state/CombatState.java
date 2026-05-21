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

    // Netty-stamped true client rotation at the moment the last attack packet
    // was sent. Captured from CapturedPacket.yaw/pitch (which the capture
    // listener now fills from s.netYaw/netPitch). HitValidation reads this so
    // it sees the real attack-time rotation even when the attacker has been
    // standing still (no PositionFrame has been pushed; frame yaw is frozen).
    public volatile float lastAttackYaw;
    public volatile float lastAttackPitch;
    public volatile boolean lastAttackHasRot;

    // ── Mace smash: the SERVER-computed damage this player just dealt with a
    //    mace, plus its receive instant. Stamped from EntityDamageByEntityEvent
    //    (server-authoritative — includes the smash bonus). MaceCheck compares
    //    this against whether a genuine fall actually happened.
    public volatile long   lastMaceHitNanos = Long.MIN_VALUE / 2;
    public volatile double lastMaceDamage;
    public volatile float  lastMaceFallDistance;
    public volatile boolean lastMaceOnGround;
    /** Counts of the times the HIGHEST-priority handler reduced an over-the-cap
     *  mace damage to vanilla (so MaceCheck can also flag the cheat behaviour
     *  rather than only the after-damage signature). */
    public volatile int    lastMaceCapPrevented;
    public volatile long   lastMaceCapPreventedNanos = Long.MIN_VALUE / 2;

    // ── Shield-bypass / backstab-teleport: stamped by BukkitStateListener when
    //    a damage-event came from an attacker whose real recent position envelope
    //    contradicts the position the server saw at the hit instant (the cheat
    //    teleported behind a shielding victim, hit, teleported back).
    public volatile long   lastBackstabBlockedNanos = Long.MIN_VALUE / 2;
    public volatile int    lastBackstabBlockedCount;
    public volatile String lastBackstabDetail = "";

    // ── Fake-crit: damage HIGHEST-priority handler stripped a critical-hit
    //    multiplier from an attack because the engine's collision-truth said
    //    the attacker was solidly grounded (no real fall). All of
    //    {NoGround, Packet, MiniJump, Blink, Timer} crit variants land here —
    //    they ALL need the server to think there was a fall, which our engine
    //    contradicts geometrically.
    public volatile long   lastFakeCritNanos = Long.MIN_VALUE / 2;
    public volatile int    lastFakeCritCount;
    public volatile String lastFakeCritDetail = "";
}
