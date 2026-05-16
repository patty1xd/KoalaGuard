package com.koalaguard.data;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * One instance per online player. Created on join, destroyed on quit
 * (see {@link DataManager}) — this is what stops the per-check map leaks
 * the old architecture suffered from.
 *
 * It also holds the shared movement / rotation / combat model so the
 * processors compute it ONCE per tick instead of every check recomputing it.
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

    // ───────────────────────── Movement model ─────────────────────────
    public Location from;
    public Location to;
    public double deltaX, deltaY, deltaZ;
    public double lastDeltaX, lastDeltaY, lastDeltaZ;
    public double deltaXZ;          // horizontal distance this tick
    public double lastDeltaXZ;
    public double accelerationXZ;   // change in horizontal speed
    public boolean onGround = true;
    public boolean lastOnGround = true;
    public boolean clientGround = true;          // value the client claims
    public boolean serverGround = true;          // value KoalaGuard computes from blocks
    public int airTicks = 0;
    public int groundTicks = 0;
    public int sinceGroundTicks = 0;
    public boolean positionChanged;
    public boolean rotationChanged;

    // ───────────────────────── Rotation model ─────────────────────────
    public float yaw, pitch;
    public float lastYaw, lastPitch;
    public float deltaYaw, deltaPitch;
    public float lastDeltaYaw, lastDeltaPitch;
    public final Deque<Float> yawSamples = new ArrayDeque<>();
    public final Deque<Float> pitchSamples = new ArrayDeque<>();

    // ───────────────────────── Combat model ───────────────────────────
    public long lastAttackMs = 0L;
    public UUID lastAttackTarget;
    public final Deque<Long> attackIntervals = new ArrayDeque<>();
    public final Deque<Long> attackTimes = new ArrayDeque<>();
    public long lastArmSwingMs = 0L;
    public int swingsSinceAttack = 0;

    // ───────────────────────── Environment flags ──────────────────────
    public boolean exemptFlying;
    public boolean exemptVehicle;
    public boolean exemptGliding;
    public boolean exemptLiquid;
    public boolean exemptClimbing;
    public boolean exemptLevitation;
    public boolean exemptSlowFalling;
    public boolean exemptRiptide;
    public boolean nearGround;            // standing on / just above a solid block

    // ───────────────────────── Timing / grace ─────────────────────────
    public long joinMs;
    public long lastTeleportMs;
    public long lastVelocityMs;
    public long lastDamageMs;
    public long lastRespawnMs;
    public long gamemodeChangeMs;
    public long lastRiptideMs;
    public long slimeBounceMs;
    public long bubbleColumnMs;
    public long elytraMs;
    public long lastWorldChangeMs;
    public long lastSlimeOrBedMs;
    public Vector pendingVelocity;        // velocity the server told the client to take
    public long pendingVelocityMs;

    public String clientBrand = "vanilla";

    private boolean alive = true;
    public boolean isAlive() { return alive; }
    public void invalidate() { alive = false; }

    // ───────────── per-check scratch storage (namespaced) ──────────────
    private final Map<String, Double> buffers = new HashMap<>();
    private final Map<String, Integer> ints = new HashMap<>();
    private final Map<String, Long> longs = new HashMap<>();
    private final Map<String, Object> objects = new HashMap<>();

    public double buffer(String key) { return buffers.getOrDefault(key, 0.0); }
    public double addBuffer(String key, double amount, double max) {
        double v = Math.min(max, buffers.getOrDefault(key, 0.0) + amount);
        buffers.put(key, v);
        return v;
    }
    public double subBuffer(String key, double amount) {
        double v = Math.max(0.0, buffers.getOrDefault(key, 0.0) - amount);
        buffers.put(key, v);
        return v;
    }
    public void setBuffer(String key, double v) { buffers.put(key, v); }

    public int getInt(String key) { return ints.getOrDefault(key, 0); }
    public int incInt(String key) { int v = ints.getOrDefault(key, 0) + 1; ints.put(key, v); return v; }
    public int addInt(String key, int amt) { int v = ints.getOrDefault(key, 0) + amt; ints.put(key, Math.max(0, v)); return ints.get(key); }
    public void setInt(String key, int v) { ints.put(key, v); }

    public long getLong(String key) { return longs.getOrDefault(key, 0L); }
    public void setLong(String key, long v) { longs.put(key, v); }

    @SuppressWarnings("unchecked")
    public <T> T obj(String key) { return (T) objects.get(key); }
    public void setObj(String key, Object v) { objects.put(key, v); }

    public long ageMs(long stampMs) {
        return stampMs <= 0 ? Long.MAX_VALUE : System.currentTimeMillis() - stampMs;
    }
}
