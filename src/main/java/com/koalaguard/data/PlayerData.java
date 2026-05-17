package com.koalaguard.data;

import com.koalaguard.engine.lag.LagCompensator;
import com.koalaguard.engine.state.PlayerState;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-player container. This class deliberately holds NO detection logic and
 * NO timing heuristics — it is the seam between the (kept) alert/punishment
 * infrastructure and the new server-authoritative engine.
 *
 * <ul>
 *   <li>{@link #engine} — the continuously tick-updated state machine that the
 *       checks actually read. Everything reconstructable lives there.</li>
 *   <li>{@link #lag} — rolling-median RTT model (no instant ping subtraction).</li>
 *   <li>grace stamps / setback fields — consumed by the kept SafetyManager and
 *       SetbackManager only.</li>
 * </ul>
 */
public final class PlayerData {

    private final UUID uuid;
    private final String name;

    public final PlayerState engine;
    public final LagCompensator lag = new LagCompensator();

    public PlayerData(Player player) {
        this.uuid = player.getUniqueId();
        this.name = player.getName();
        this.engine = new PlayerState(uuid, name);
        long now = System.currentTimeMillis();
        this.joinMs = now;
        this.lastTeleportMs = now;
    }

    public UUID getUuid() { return uuid; }
    public String getName() { return name; }

    // ───────────────── client identity ─────────────────
    public volatile String clientBrand = "vanilla";
    public volatile String packetBrand = "vanilla";
    public volatile boolean flagBadBrand;

    // ───────────────── transaction / tick clock ─────────────────
    /** transaction id -> send nanoTime, pending until the client echoes Pong. */
    public final Map<Integer, Long> pendingTransactions = new ConcurrentHashMap<>();
    public volatile int transactionPing = -1;       // last raw RTT (ms)
    public volatile long lastPongMs;
    public volatile long confirmedTransactions;     // authoritative monotonic tick clock
    public volatile long lastTransactionSentMs;
    public volatile long serverTicks;

    // ───────────────── grace windows (set by BukkitStateListener) ─────────────────
    public volatile long joinMs, lastTeleportMs, lastVelocityMs, lastDamageMs, lastRespawnMs;
    public volatile long gamemodeChangeMs, lastWorldChangeMs, lastRiptideMs;
    public volatile long slimeBounceMs, bubbleColumnMs, elytraMs, lastSlimeOrBedMs;
    public volatile Vector pendingVelocity;
    public volatile long pendingVelocityMs;

    // ───────────────── setback / lagback ─────────────────
    public volatile Location lastValidLocation;
    public volatile boolean setbackPending;
    public volatile long lastSetbackMs;
    public volatile int setbackStreak;

    // ───────────────── silent combat cancellation ─────────────────
    /** While now < this, the netty layer drops this player's attack packets
     *  (combat analog of a movement setback — set only after a CONFIRMED,
     *  persistent combat violation). */
    public volatile long combatCancelUntilMs;

    private volatile boolean alive = true;
    public boolean isAlive() { return alive; }
    public void invalidate() { alive = false; }
}
