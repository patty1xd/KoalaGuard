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

    private final KoalaGuard plugin;
    // UUID -> checkName -> violation count
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

        // Staff alert
        String alert = "§6§l[KoalaGuard Alert] §f" + player.getName()
                + " §7failed §e" + checkName
                + " §7(VL: §c" + vl + "§7/§c" + maxVl + "§7)"
                + (detail != null && !detail.isEmpty() ? " §8[" + detail + "]" : "");

        for (Player online : Bukkit.getOnlinePlayers()) {
            if (online.hasPermission("koalaguard.alerts")) {
                online.sendMessage(alert);
            }
        }

        plugin.getLogger().info("[Alert] " + player.getName() + " | " + checkName + " VL:" + vl + " " + (detail != null ? detail : ""));

        // Check ban threshold
        if (vl >= maxVl) {
            String banDuration = plugin.getConfig().getString("checks." + checkName.toLowerCase() + ".ban-duration", "7d");
            plugin.getBanManager().ban(player, checkName, banDuration);
            violations.get(uuid).remove(checkName);
        }
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
