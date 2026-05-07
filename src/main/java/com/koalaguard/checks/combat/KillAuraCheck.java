package com.koalaguard.checks.combat;

import com.koalaguard.KoalaGuard;
import com.koalaguard.checks.Check;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.entity.EntityDamageByEntityEvent;

import java.util.*;

public class KillAuraCheck extends Check {

    // Track rotation snapshots per player
    private final Map<UUID, Float> lastYaw = new HashMap<>();
    private final Map<UUID, Float> lastPitch = new HashMap<>();
    private final Map<UUID, Long> lastHitTime = new HashMap<>();
    private final Map<UUID, List<Float>> yawHistory = new HashMap<>();

    public KillAuraCheck(KoalaGuard plugin) { super(plugin, "killaura"); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        if (!isEnabled()) return;
        if (!(event.getDamager() instanceof Player player)) return;
        if (player.hasPermission("koalaguard.bypass")) return;
        if (!(event.getEntity() instanceof LivingEntity)) return;

        UUID uuid = player.getUniqueId();
        long now = System.currentTimeMillis();

        float currentYaw = player.getLocation().getYaw();
        float currentPitch = player.getLocation().getPitch();
        float lastY = lastYaw.getOrDefault(uuid, currentYaw);
        float lastP = lastPitch.getOrDefault(uuid, currentPitch);

        long lastHit = lastHitTime.getOrDefault(uuid, 0L);
        long timeDiff = now - lastHit;

        // Check 1: Snapping (instant large rotation + immediate hit)
        float yawDelta = Math.abs(currentYaw - lastY);
        if (yawDelta > 180) yawDelta = 360 - yawDelta;

        if (yawDelta > 90 && timeDiff < 100) {
            flag(player, "snap_yaw=" + String.format("%.1f", yawDelta));
        }

        // Check 2: Multi-target aura (hitting entity not in front)
        Entity target = event.getEntity();
        double angle = getAngleTo(player, target);
        if (angle > 90) {
            flag(player, "angle=" + String.format("%.1f", angle));
        }

        // Check 3: Perfect aim consistency (suspiciously low variance in yaw changes)
        List<Float> history = yawHistory.computeIfAbsent(uuid, k -> new ArrayList<>());
        history.add(yawDelta);
        if (history.size() > 20) {
            history.remove(0);
            double variance = computeVariance(history);
            if (variance < 0.5 && history.size() == 20) {
                flag(player, "low_variance=" + String.format("%.3f", variance));
            }
        }

        lastYaw.put(uuid, currentYaw);
        lastPitch.put(uuid, currentPitch);
        lastHitTime.put(uuid, now);
    }

    private double getAngleTo(Player player, Entity target) {
        var dir = player.getLocation().getDirection().normalize();
        var toTarget = target.getLocation().toVector()
                .subtract(player.getLocation().toVector()).normalize();
        double dot = dir.dot(toTarget);
        return Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, dot))));
    }

    private double computeVariance(List<Float> values) {
        double mean = values.stream().mapToDouble(Float::doubleValue).average().orElse(0);
        return values.stream().mapToDouble(v -> Math.pow(v - mean, 2)).average().orElse(0);
    }
}
