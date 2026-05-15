package com.koalaguard.util;

import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class PlayerStateTracker {

    // --- Existing states ---
    private final Map<UUID, Long> lastJoinMs        = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastTeleportMs    = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastVelocityMs    = new ConcurrentHashMap<>();
    private final Map<UUID, Vector> lastVelocity    = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastDamageTakenMs = new ConcurrentHashMap<>();

    // --- New states for reduced false positives ---
    private final Map<UUID, Long> lastRespawnMs       = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastElytraLandMs    = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastRiptideMs       = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastBubbleColumnMs  = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastSlimeBounceMs   = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastGameModeChangeMs = new ConcurrentHashMap<>();

    // --- Events ---

    public void onJoin(Player p)          { stamp(lastJoinMs, p); }
    public void onTeleport(Player p)      { stamp(lastTeleportMs, p); }
    public void onDamageTaken(Player p)   { stamp(lastDamageTakenMs, p); }
    public void onRespawn(Player p)       { stamp(lastRespawnMs, p); }
    public void onElytraLand(Player p)    { stamp(lastElytraLandMs, p); }
    public void onRiptide(Player p)       { stamp(lastRiptideMs, p); }
    public void onBubbleColumn(Player p)  { stamp(lastBubbleColumnMs, p); }
    public void onSlimeBounce(Player p)   { stamp(lastSlimeBounceMs, p); }
    public void onGameModeChange(Player p){ stamp(lastGameModeChangeMs, p); }

    public void onVelocity(Player p, Vector velocity) {
        if (p == null) return;
        UUID id = p.getUniqueId();
        lastVelocityMs.put(id, System.currentTimeMillis());
        if (velocity != null) lastVelocity.put(id, velocity.clone());
    }

    // --- Queries ---

    public boolean recentlyJoined(Player p, long windowMs)         { return within(lastJoinMs, p, windowMs); }
    public boolean recentlyTeleported(Player p, long windowMs)     { return within(lastTeleportMs, p, windowMs); }
    public boolean recentlyHadVelocity(Player p, long windowMs)    { return within(lastVelocityMs, p, windowMs); }
    public boolean recentlyTookDamage(Player p, long windowMs)     { return within(lastDamageTakenMs, p, windowMs); }
    public boolean recentlyRespawned(Player p, long windowMs)      { return within(lastRespawnMs, p, windowMs); }
    public boolean recentlyLandedFromElytra(Player p, long windowMs){ return within(lastElytraLandMs, p, windowMs); }
    public boolean recentlyUsedRiptide(Player p, long windowMs)    { return within(lastRiptideMs, p, windowMs); }
    public boolean recentlyInBubbleColumn(Player p, long windowMs) { return within(lastBubbleColumnMs, p, windowMs); }
    public boolean recentlySlimeBounced(Player p, long windowMs)   { return within(lastSlimeBounceMs, p, windowMs); }
    public boolean recentlyChangedGameMode(Player p, long windowMs){ return within(lastGameModeChangeMs, p, windowMs); }

    public Vector getLastVelocity(Player p) {
        if (p == null) return null;
        Vector v = lastVelocity.get(p.getUniqueId());
        return v == null ? null : v.clone();
    }

    public void clear(UUID uuid) {
        if (uuid == null) return;
        lastJoinMs.remove(uuid);
        lastTeleportMs.remove(uuid);
        lastVelocityMs.remove(uuid);
        lastVelocity.remove(uuid);
        lastDamageTakenMs.remove(uuid);
        lastRespawnMs.remove(uuid);
        lastElytraLandMs.remove(uuid);
        lastRiptideMs.remove(uuid);
        lastBubbleColumnMs.remove(uuid);
        lastSlimeBounceMs.remove(uuid);
        lastGameModeChangeMs.remove(uuid);
    }

    // --- Helpers ---

    private static void stamp(Map<UUID, Long> map, Player p) {
        if (p == null) return;
        map.put(p.getUniqueId(), System.currentTimeMillis());
    }

    private static boolean within(Map<UUID, Long> map, Player p, long windowMs) {
        if (p == null) return false;
        Long t = map.get(p.getUniqueId());
        return t != null && System.currentTimeMillis() - t <= windowMs;
    }
}
