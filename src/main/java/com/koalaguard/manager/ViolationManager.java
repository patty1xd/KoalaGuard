package com.koalaguard.manager;

import com.koalaguard.KoalaGuard;
import com.koalaguard.alert.Alert;
import com.koalaguard.check.Check;
import com.koalaguard.data.PlayerData;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Accumulates decimal violation levels per (player, check), fires alerts and
 * hands off to {@link PunishmentManager} once the limit is reached. State is
 * bounded by online players and cleared on quit — no leaks.
 */
public final class ViolationManager {

    private final KoalaGuard plugin;
    private final PunishmentManager punishments;

    private final Map<UUID, Map<String, Double>> vls = new ConcurrentHashMap<>();
    private final Map<UUID, Map<String, Long>> cooldowns = new ConcurrentHashMap<>();

    private BukkitTask decayTask;

    public ViolationManager(KoalaGuard plugin) {
        this.plugin = plugin;
        this.punishments = new PunishmentManager(plugin);
        startDecayTask();
    }

    public PunishmentManager punishments() { return punishments; }

    public void fail(Check check, PlayerData data, Player player, String detail) {
        final UUID uuid = player.getUniqueId();
        final String key = check.getName();
        final long now = System.currentTimeMillis();

        long cd = plugin.getConfig().getLong("safety.flag-cooldown-ms", 600L);
        Map<String, Long> pcd = cooldowns.computeIfAbsent(uuid, k -> new ConcurrentHashMap<>());
        if (now - pcd.getOrDefault(key, 0L) < cd) return;
        pcd.put(key, now);

        Map<String, Double> perPlayer = vls.computeIfAbsent(uuid, k -> new ConcurrentHashMap<>());
        double vl = perPlayer.merge(key, check.vlPerFlag(), Double::sum);
        int vlInt = (int) Math.ceil(vl);
        int max = check.maxViolations();

        double severity = Math.min(1.0, vl / Math.max(1, max));
        boolean punish = vlInt >= max;

        Alert alert = buildAlert(check, player, detail, vlInt, max, severity, false, "");
        plugin.getAlertManager().sendAlert(alert);

        if (punish) {
            perPlayer.put(key, 0.0);
            punishments.punish(check, player, vlInt, severity, detail);
        }
    }

    public void reward(Check check, PlayerData data) {
        Map<String, Double> p = vls.get(data.getUuid());
        if (p == null) return;
        Double v = p.get(check.getName());
        if (v == null) return;
        double nv = v - plugin.getConfig().getDouble("reward-decay", 0.05);
        if (nv <= 0) p.remove(check.getName());
        else p.put(check.getName(), nv);
    }

    public Alert buildAlert(Check check, Player player, String detail, int vl, int max,
                            double severity, boolean punishment, String punishmentType) {
        Location l = player.getLocation();
        PlayerData d = plugin.getDataManager().get(player);
        return new Alert(
                player.getName(),
                player.getUniqueId().toString(),
                check.getName(),
                check.getCategory().display(),
                check.getDescription(),
                detail,
                l.getWorld() != null ? l.getWorld().getName() : "?",
                l.getX(), l.getY(), l.getZ(),
                vl, max,
                plugin.getMetrics().pingMs(player),
                plugin.getMetrics().tps(),
                d != null ? d.clientBrand : "unknown",
                severity,
                punishment, punishmentType,
                System.currentTimeMillis());
    }

    public int getViolations(UUID uuid, String check) {
        Map<String, Double> p = vls.get(uuid);
        if (p == null) return 0;
        return (int) Math.ceil(p.getOrDefault(check, 0.0));
    }

    public Map<String, Integer> getAllViolations(UUID uuid) {
        Map<String, Double> p = vls.get(uuid);
        if (p == null) return Map.of();
        Map<String, Integer> out = new HashMap<>();
        p.forEach((k, v) -> { if (v >= 0.5) out.put(k, (int) Math.ceil(v)); });
        return out;
    }

    public void clearPlayer(UUID uuid) {
        vls.remove(uuid);
        cooldowns.remove(uuid);
    }

    private void startDecayTask() {
        int interval = plugin.getConfig().getInt("decay-interval", 30);
        double amount = plugin.getConfig().getDouble("violation-decay", 1.0);
        decayTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            for (Map<String, Double> per : vls.values()) {
                per.replaceAll((c, v) -> Math.max(0, v - amount));
                per.values().removeIf(v -> v <= 0);
            }
        }, interval * 20L, interval * 20L);
    }

    public void shutdown() {
        if (decayTask != null) decayTask.cancel();
        vls.clear();
        cooldowns.clear();
    }
}
