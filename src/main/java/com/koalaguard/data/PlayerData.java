package com.koalaguard.data;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * One instance per online player.
 *
 * Split in two halves:
 *  • "packet" fields — written ONLY by the netty packet thread
 *    ({@code PacketProcessor}); per-connection packets are ordered so there is
 *    a single writer. Primitives are volatile; collections are concurrent.
 *  • "model" fields — derived ONLY on the main thread (MovementTask /
 *    CombatProcessor) from the packet snapshot, then read by checks.
 */
public final class PlayerData {

    private final UUID uuid;
    private final String name;

    public PlayerData(Player player) {
        this.uuid = player.getUniqueId();
        this.name = player.getName();
        long now = System.currentTimeMillis();
        this.joinMs = now;
        this.lastTeleportMs = now;
    }

    public UUID getUuid() { return uuid; }
    public String getName() { return name; }

    // ═══════════════ PACKET CAPTURE (netty thread writes) ═══════════════
    public volatile double pX, pY, pZ;
    public volatile boolean pHasPos;
    public volatile boolean pOnGround = true;
    public volatile float pYaw, pPitch;
    public volatile boolean pHasRot;
    public volatile boolean sprinting, sneaking;
    public volatile long lastSwingMs;
    public volatile long lastAttackPacketMs;
    public volatile boolean usingItem;
    public volatile long useStartMs;
    public volatile int lastHeldSlot = -1;
    public volatile long lastHeldSlotMs;
    public volatile long lastSlotChangeMs;
    public volatile long lastOffhandSwapMs;
    public volatile long lastClickWindowMs;
    public volatile long lastDiggingStartMs;
    public volatile String packetBrand = "vanilla";
    public volatile boolean flagBadBrand;

    // ── transaction / keepalive (lag compensation) ──
    public volatile int transactionPing = -1;       // round-trip ms from Ping/Pong
    public volatile long lastPongMs;
    public volatile long confirmedTransactions;     // monotonically rising tick clock
    public volatile long lastTransactionSentMs;
    public volatile long serverTicks;               // authoritative server-tick counter
    public volatile long flyingPacketCount;         // monotonic client movement-packet counter
    /** transaction id -> send nanoTime (pending, awaiting Pong). */
    public final Map<Integer, Long> pendingTransactions = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * One frame per position-bearing client packet (= one client movement
     * tick). Drained on the main thread so deltas are EXACT per client tick
     * and never aliased to the server tick. Frame = {x,y,z,yaw,pitch,ground}.
     */
    public final java.util.Queue<double[]> moveQueue = new java.util.concurrent.ConcurrentLinkedQueue<>();
    // last processed frame (main thread only)
    public double prevX, prevY, prevZ;
    public float prevYaw, prevPitch;
    public boolean moveInit;

    public final ConcurrentLinkedDeque<Long> flyingTimes = new ConcurrentLinkedDeque<>();
    public final ConcurrentLinkedDeque<Long> attackPacketTimes = new ConcurrentLinkedDeque<>();
    public final ConcurrentLinkedDeque<Long> swingTimes = new ConcurrentLinkedDeque<>();
    /** entityId -> last attack-packet time (multi-aura). */
    private final Map<Integer, Long> recentAttacked = new java.util.concurrent.ConcurrentHashMap<>();
    private final AtomicInteger flyingThisWindow = new AtomicInteger();

    /** Rotation history straight from PLAYER_FLYING — the real client aim. */
    private final Deque<float[]> rotHistory = new ArrayDeque<>();

    public synchronized void pushRotation(float yaw, float pitch) {
        rotHistory.addLast(new float[]{yaw, pitch});
        while (rotHistory.size() > 64) rotHistory.removeFirst();
    }

    /** @return absolute yaw deltas (deg) over recent packets, oldest→newest. */
    public synchronized java.util.List<Float> yawDeltas(int max) {
        java.util.List<Float> out = new java.util.ArrayList<>();
        float[] prev = null;
        for (float[] r : rotHistory) {
            if (prev != null) {
                float d = Math.abs(wrap(r[0] - prev[0]));
                out.add(d);
            }
            prev = r;
        }
        while (out.size() > max) out.remove(0);
        return out;
    }

    public synchronized java.util.List<Float> pitchDeltas(int max) {
        java.util.List<Float> out = new java.util.ArrayList<>();
        float[] prev = null;
        for (float[] r : rotHistory) {
            if (prev != null) out.add(Math.abs(r[1] - prev[1]));
            prev = r;
        }
        while (out.size() > max) out.remove(0);
        return out;
    }

    public void markAttacked(int entityId) {
        long now = System.currentTimeMillis();
        recentAttacked.values().removeIf(t -> now - t > 400);
        recentAttacked.put(entityId, now);
    }
    public int distinctRecentTargets() {
        long now = System.currentTimeMillis();
        recentAttacked.values().removeIf(t -> now - t > 400);
        return recentAttacked.size();
    }

    private static float wrap(float a) {
        a %= 360f; if (a >= 180f) a -= 360f; if (a < -180f) a += 360f; return a;
    }

    // ═══════════════ MOVEMENT MODEL (main thread) ═══════════════
    public Location from, to;
    public double deltaX, deltaY, deltaZ;
    public double lastDeltaX, lastDeltaY, lastDeltaZ;
    public double deltaXZ, lastDeltaXZ, accelerationXZ;
    public boolean onGround = true, lastOnGround = true;
    public boolean clientGround = true, serverGround = true;
    public int airTicks, groundTicks, sinceGroundTicks;
    public boolean positionChanged, rotationChanged;

    public float yaw, pitch, lastYaw, lastPitch;
    public float deltaYaw, deltaPitch, lastDeltaYaw, lastDeltaPitch;
    public final Deque<Float> yawSamples = new ArrayDeque<>();
    public final Deque<Float> pitchSamples = new ArrayDeque<>();

    // ═══════════════ COMBAT MODEL ═══════════════
    public long lastAttackMs;
    public UUID lastAttackTarget;
    public final Deque<Long> attackIntervals = new ArrayDeque<>();
    public final Deque<Long> attackTimes = new ArrayDeque<>();
    public long lastArmSwingMs;
    public int swingsSinceAttack;

    // ═══════════════ ENVIRONMENT / GRACE ═══════════════
    public boolean exemptFlying, exemptVehicle, exemptGliding, exemptClimbing;
    public boolean exemptLiquid, exemptLevitation, exemptSlowFalling, exemptRiptide;
    public boolean nearGround;

    public long joinMs, lastTeleportMs, lastVelocityMs, lastDamageMs, lastRespawnMs;
    public long gamemodeChangeMs, lastRiptideMs, slimeBounceMs, bubbleColumnMs;
    public long elytraMs, lastWorldChangeMs, lastSlimeOrBedMs;
    public Vector pendingVelocity;
    public long pendingVelocityMs;
    public String clientBrand = "vanilla";

    // ═══════════════ SETBACK / LAGBACK ═══════════════
    public Location lastValidLocation;     // last position that passed prediction
    public volatile boolean setbackPending;
    public long lastSetbackMs;
    public int setbackStreak;

    // ═══════════════ TOTEM CYCLE (AutoTotem) ═══════════════
    public volatile long totemPopMs;
    public volatile boolean awaitingTotem;
    public final Deque<Long> totemReequipSamples = new ArrayDeque<>();

    private boolean alive = true;
    public boolean isAlive() { return alive; }
    public void invalidate() { alive = false; }

    // ═══════════════ per-check scratch ═══════════════
    private final Map<String, Double> buffers = new HashMap<>();
    private final Map<String, Integer> ints = new HashMap<>();
    private final Map<String, Long> longs = new HashMap<>();
    private final Map<String, Object> objects = new HashMap<>();

    public double buffer(String k) { return buffers.getOrDefault(k, 0.0); }
    public double addBuffer(String k, double a, double max) {
        double v = Math.min(max, buffers.getOrDefault(k, 0.0) + a); buffers.put(k, v); return v;
    }
    public double subBuffer(String k, double a) {
        double v = Math.max(0.0, buffers.getOrDefault(k, 0.0) - a); buffers.put(k, v); return v;
    }
    public void setBuffer(String k, double v) { buffers.put(k, v); }
    public int getInt(String k) { return ints.getOrDefault(k, 0); }
    public int incInt(String k) { int v = getInt(k) + 1; ints.put(k, v); return v; }
    public int addInt(String k, int a) { ints.put(k, Math.max(0, getInt(k) + a)); return getInt(k); }
    public void setInt(String k, int v) { ints.put(k, v); }
    public long getLong(String k) { return longs.getOrDefault(k, 0L); }
    public void setLong(String k, long v) { longs.put(k, v); }
    @SuppressWarnings("unchecked")
    public <T> T obj(String k) { return (T) objects.get(k); }
    public void setObj(String k, Object v) { objects.put(k, v); }

    public long ageMs(long stamp) { return stamp <= 0 ? Long.MAX_VALUE : System.currentTimeMillis() - stamp; }
}
