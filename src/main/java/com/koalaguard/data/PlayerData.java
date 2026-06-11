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
    /** Brand as actually RECEIVED via minecraft:brand. Starts empty — "" means
     *  "never sent one", which the (config-gated, default-off) empty-brand
     *  evasion rule in BadPacketsBrand keys on. The previous "vanilla" default
     *  made that rule dead code: the field could never read as missing. */
    public volatile String packetBrand = "";
    /** Empty-brand evasion already reported for this join (flag once, not
     *  once per cooldown forever). */
    public volatile boolean emptyBrandFlagged;
    public volatile boolean flagBadBrand;
    /** A known cheat-client plugin channel was registered/used (Layer 1). */
    public volatile boolean flagBadChannel;
    public volatile String badChannel = "";
    /** Brand string is literally "vanilla" — used in combination with mod-
     *  loader channel registration to detect cheats that lie about brand. */
    public volatile boolean brandVanilla;

    /** Clip / phase-teleport (.vclip/.hclip): moved through solid with no
     *  server teleport. Stamped by the engine, consumed by ClipCheck. */
    public volatile long clipSeq;
    public volatile String clipDetail = "";

    /** Big un-graced single-tick displacement (>8 blocks, not through solid).
     *  Stamped by EngineTask BEFORE the >8 baseline reset eats the frame, so
     *  ClickTpCheck can see large free-air teleports. */
    public volatile long clickTpSeq;
    public volatile String clickTpDetail = "";

    /** Meteor ClickTp "StatusOnly burst" fingerprint — N consecutive
     *  PLAYER_FLYING packets with no pos/rot inside a sub-50ms window, the
     *  signature of the cheat's decoy-then-pos pattern. Stamped by
     *  PacketCaptureListener, consumed by ClickTpCheck. */
    public volatile long clickTpBurstSeq;
    public volatile int  clickTpBurstSize;

    /** Protocol-illegal movement packet (NaN/Infinity coordinates or rotation,
     *  out-of-range pitch, coordinate past the vanilla world limit). The packet
     *  itself is CANCELLED on the netty thread — Mojang's handler never sees
     *  it — and this stamp is consumed by BadPacketsSanity on the main thread.
     *  A vanilla client cannot emit any of these, so the flag is conclusive. */
    public volatile long sanitySeq;
    public volatile String sanityDetail = "";

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
    /** Wall-clock of the most recent death event — paired with lastRespawnMs
     *  for AutoRespawn detection (sub-100ms gap = bot reflex). */
    public volatile long lastDeathMs;
    public volatile long lastRespawnGapMs = -1;        // delta the last time we measured
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

    /** While now < this, the netty layer drops this player's position-bearing
     *  movement packets — set right after a setback so cheat packets queued
     *  during the same tick (blink burst, backstab return) cannot immediately
     *  undo the rubber-band before Mojang's processor sees them. */
    public volatile long movementCancelUntilMs;

    /** Raw netty-thread byte / packet-id stats, populated by the
     *  {@link com.koalaguard.engine.netty.RawPacketSniffer} channel handler.
     *  Null until the sniffer attaches (the handler instantiates on first
     *  use, so the field stays null for sessions before injection runs). */
    public volatile com.koalaguard.engine.netty.RawPacketStats rawStats;

    private volatile boolean alive = true;
    public boolean isAlive() { return alive; }
    public void invalidate() { alive = false; }
}
