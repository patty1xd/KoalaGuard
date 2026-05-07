package com.koalaguard.managers;

import com.koalaguard.KoalaGuard;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class ViolationManager {

  private static final String DEFAULT_PREFIX = "§c[KoalaGuard] §b";

  private final KoalaGuard plugin;

  // uuid -> (checkName -> vl)
  private final Map<UUID, Map<String, Integer>> violations = new ConcurrentHashMap<>();

  private BukkitTask decayTask;

  public ViolationManager(KoalaGuard plugin) {
    this.plugin = plugin;
    startDecayTask();
  }

  public void flag(Player player, String checkName, String detail) {
    if (player == null || checkName == null || checkName.isBlank()) return;
    if (player.hasPermission("koalaguard.bypass")) return;

    final String checkKey = checkName.toLowerCase();
    final UUID uuid = player.getUniqueId();

    final Map<String, Integer> perPlayer =
        violations.computeIfAbsent(uuid, k -> new ConcurrentHashMap<>());

    final int vl = perPlayer.merge(checkKey, 1, Integer::sum);
    final int maxVl = plugin.getConfig().getInt("checks." + checkKey + ".max-violations", 10);

    final String prefix = plugin.getConfig().getString("messages.prefix", DEFAULT_PREFIX);
    final String alertPerm = plugin.getConfig().getString("alerts.permission", "koalaguard.alerts");
    final boolean consoleAlerts = plugin.getConfig().getBoolean("alerts.console", true);

    final String msg =
        prefix
            + player.getName()
            + " failed "
            + checkName
            + " (VL: "
            + vl
            + "/"
            + maxVl
            + ")"
            + (detail != null && !detail.isBlank() ? " | " + detail : "");

    for (Player online : Bukkit.getOnlinePlayers()) {
      if (online.hasPermission(alertPerm)) online.sendMessage(msg);
    }
    if (consoleAlerts) {
      plugin
          .getLogger()
          .info(
              "[Alert] "
                  + player.getName()
                  + " | "
                  + checkName
                  + " VL:"
                  + vl
                  + (detail != null && !detail.isBlank() ? " | " + detail : ""));
    }

    if (vl >= maxVl) {
      final String punishment =
          plugin.getConfig().getString("checks." + checkKey + ".punishment", "kick");
      applyPunishment(player, checkName, punishment);

      // reset only this check’s VL
      perPlayer.remove(checkKey);
      if (perPlayer.isEmpty()) violations.remove(uuid);
    }
  }

  public void flag(Player player, String checkName) {
    flag(player, checkName, "");
  }

  public int getViolations(UUID uuid, String checkName) {
    if (uuid == null || checkName == null) return 0;
    Map<String, Integer> playerVls = violations.get(uuid);
    if (playerVls == null) return 0;
    return playerVls.getOrDefault(checkName.toLowerCase(), 0);
  }

  public Map<String, Integer> getAllViolations(UUID uuid) {
    if (uuid == null) return Map.of();
    Map<String, Integer> vls = violations.get(uuid);
    return vls == null ? Map.of() : new HashMap<>(vls);
  }

  public void clearPlayer(UUID uuid) {
    if (uuid == null) return;
    violations.remove(uuid);
  }

  private void applyPunishment(Player player, String checkName, String punishment) {
    final String prefix = plugin.getConfig().getString("messages.prefix", DEFAULT_PREFIX);
    final boolean broadcastPunishments = plugin.getConfig().getBoolean("punishments.broadcast", false);
    final String broadcastPerm =
        plugin.getConfig().getString("punishments.broadcast-permission", "koalaguard.alerts");

    switch (punishment == null ? "kick" : punishment.toLowerCase()) {
      case "ban" -> {
        String duration =
            plugin.getConfig().getString("checks." + checkName.toLowerCase() + ".ban-duration", "7d");
        plugin.getBanManager().ban(player, checkName, duration);
      }
      case "warn" -> player.sendMessage(prefix + "Warning: suspicious activity detected (" + checkName + ")");
      case "kick" -> {
        if (broadcastPunishments) {
          for (Player online : Bukkit.getOnlinePlayers()) {
            if (online.hasPermission(broadcastPerm)) {
              online.sendMessage(prefix + player.getName() + " was punished | Reason: " + checkName);
            }
          }
        }

        Bukkit.getScheduler()
            .runTask(
                plugin,
                () -> {
                  if (player.isOnline()) {
                    player.kickPlayer("§c[KoalaGuard]\n§bYou were kicked for: §f" + checkName);
                  }
                });
      }
      default -> {
        // unknown punishment -> do nothing (safe default)
      }
    }
  }

  private void startDecayTask() {
    int intervalSeconds = plugin.getConfig().getInt("decay-interval", 60);
    int decayAmount = plugin.getConfig().getInt("violation-decay", 1);

    // Keep this on the main thread: checks run on main thread and this avoids races.
    decayTask =
        Bukkit.getScheduler()
            .runTaskTimer(
                plugin,
                () -> {
                  for (Map.Entry<UUID, Map<String, Integer>> entry : violations.entrySet()) {
                    Map<String, Integer> playerVls = entry.getValue();
                    playerVls.replaceAll((check, vl) -> Math.max(0, vl - decayAmount));
                    playerVls.entrySet().removeIf(e -> e.getValue() <= 0);
                    if (playerVls.isEmpty()) violations.remove(entry.getKey());
                  }
                },
                intervalSeconds * 20L,
                intervalSeconds * 20L);
  }

  public void shutdown() {
    if (decayTask != null) decayTask.cancel();
  }
}
