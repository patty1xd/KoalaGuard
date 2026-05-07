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

    private static final String PREFIX = "§c[KoalaGuard] §b";

    private final KoalaGuard plugin;
    private final Map<UUID, Map<String, Integer>> violations = new ConcurrentHashMap<>();
    private BukkitTask decayTask;

    public ViolationManager(KoalaGuard plugin) {
        this.plugin = plugin;
        startDecayTask();
    }

    public void flag(Player player, String checkName, String detail) {
        if (player.hasPermission("koalaguard.bypass")) return;

        UUID uuid = player.getUniqueId();
        violations.computeIfAbsent(uuid, k -> new ConcurrentHashMap<>());
        int vl = violations.get(uuid).merge(checkName, 1, Integer::sum);
        int maxVl = plugin.getConfig().getInt("checks." + checkName.toLowerCase() + ".max-violations", 10);

        String alert = PREFIX + player.getName()
                + " failed " + checkName
                + " (VL: " + vl + "/" + maxVl + ")"
                + (detail != null && !detail.isEmpty() ? " | " + detail : "");

        for (Player online : Bukkit.getOnlinePlayers()) {
            if (online.hasPermission("koalaguard.alerts")) {
                online.sendMessage(alert);
            }
        }

        plugin.getLogger().info("[Alert] " + player.getName() + " | " + checkName
                + " VL:" + vl + (detail != null && !detail.isEmpty() ? " | " + detail : ""));

        if (vl >= maxVl) {
            String punishment = plugin.getConfig().getString(
                    "checks." + checkName.toLowerCase() + ".punishment", "kick");
            applyPunishment(player, checkName, punishment);
            violations.get(uuid).remove(checkName);
        }
    }

    private void applyPunishment(Player player, String checkName, String punishment) {
        switch (punishment.toLowerCase()) {
            case "ban" -> {
                String duration = plugin.getConfig().getString(
                        "checks." + checkName.toLowerCase() + ".ban-duration", "7d");
                plugin.getBanManager().ban(player, checkName, duration);
            }
            case "kick" -> {
                Bukkit.broadcastMessage(PREFIX + player.getName() + " was punished | Reason: " + checkName);
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (player.isOnline()) player.kickPlayer(
                            "§c[KoalaGuard]\n§bYou were kicked for: §f" + checkName);
                });
            }
            case "warn" -> {
                player.sendMessage(PREFIX + "Warning: suspicious activity detected (" + checkName + ")");
            }
        }
    }

    public void flag(Player player, String checkName) {
        flag(player, checkName, "");
    }

    public int getViolations(UUID uuid, String checkName) {
        Map<String, Integer> playerVls = violations.get(uuid);
        if (playerVls == null) return 0;
        return playerVls.getOrDefault(checkName, 0);
    }

    public Map<String, Integer> getAllViolations(UUID uuid) {
        return violations.getOrDefault(uuid, new HashMap<>());
    }

    public void clearPlayer(UUID uuid) {
        violations.remove(uuid);
    }

    private void startDecayTask() {
        int intervalSeconds = plugin.getConfig().getInt("decay-interval", 60);
        int decayAmount = plugin.getConfig().getInt("violation-decay", 1);

        decayTask = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, () -> {
            for (Map<String, Integer> playerVls : violations.values()) {
                playerVls.replaceAll((check, vl) -> Math.max(0, vl - decayAmount));
                playerVls.entrySet().removeIf(e -> e.getValue() <= 0);
            }
        }, intervalSeconds * 20L, intervalSeconds * 20L);
    }

    public void shutdown() {
        if (decayTask != null) decayTask.cancel();
    }
}
