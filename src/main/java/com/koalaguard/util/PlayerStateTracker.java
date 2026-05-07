package com.koalaguard.util;

import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class PlayerStateTracker {

  private final Map<UUID, Long> lastJoinMs = new ConcurrentHashMap<>();
  private final Map<UUID, Long> lastTeleportMs = new ConcurrentHashMap<>();
  private final Map<UUID, Long> lastVelocityMs = new ConcurrentHashMap<>();
  private final Map<UUID, Vector> lastVelocity = new ConcurrentHashMap<>();
  private final Map<UUID, Long> lastDamageTakenMs = new ConcurrentHashMap<>();

  public void onJoin(Player player) {
    if (player == null) return;
    lastJoinMs.put(player.getUniqueId(), System.currentTimeMillis());
  }

  public void onTeleport(Player player) {
    if (player == null) return;
    lastTeleportMs.put(player.getUniqueId(), System.currentTimeMillis());
  }

  public void onVelocity(Player player, Vector velocity) {
    if (player == null) return;
    UUID uuid = player.getUniqueId();
    lastVelocityMs.put(uuid, System.currentTimeMillis());
    if (velocity != null) lastVelocity.put(uuid, velocity.clone());
  }

  public void onDamageTaken(Player player) {
    if (player == null) return;
    lastDamageTakenMs.put(player.getUniqueId(), System.currentTimeMillis());
  }

  public boolean recentlyJoined(Player player, long windowMs) {
    return within(lastJoinMs, player, windowMs);
  }

  public boolean recentlyTeleported(Player player, long windowMs) {
    return within(lastTeleportMs, player, windowMs);
  }

  public boolean recentlyHadVelocity(Player player, long windowMs) {
    return within(lastVelocityMs, player, windowMs);
  }

  public boolean recentlyTookDamage(Player player, long windowMs) {
    return within(lastDamageTakenMs, player, windowMs);
  }

  public Vector getLastVelocity(Player player) {
    if (player == null) return null;
    Vector v = lastVelocity.get(player.getUniqueId());
    return v == null ? null : v.clone();
  }

  public void clear(UUID uuid) {
    if (uuid == null) return;
    lastJoinMs.remove(uuid);
    lastTeleportMs.remove(uuid);
    lastVelocityMs.remove(uuid);
    lastVelocity.remove(uuid);
    lastDamageTakenMs.remove(uuid);
  }

  private static boolean within(Map<UUID, Long> map, Player player, long windowMs) {
    if (player == null) return false;
    Long t = map.get(player.getUniqueId());
    if (t == null) return false;
    return System.currentTimeMillis() - t <= windowMs;
  }
}

