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

    private final Map<UUID, Float>       lastYaw      = new HashMap<>();
    private final Map<UUID, Float>       lastPitch    = new HashMap<>();
    private final Map<UUID, Long>        lastHitTime  = new HashMap<>();
    private final Map<UUID, List<Float>> yawHistory   = new HashMap<>();

    // Multi-target tracking: how many distinct entities hit in a short window
    private final Map<UUID, Set<UUID>>   recentTargets   = new HashMap<>();
    private final Map<UUID, Long>        targetWindowStart = new HashMap<>();

    public KillAuraCheck(KoalaGuard plugin) { super(plugin, "killaura"); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        if (!isEnabled()) return;
        if (!(event.getDamager() instanceof Player player)) return;
        if (isExempt(player)) return;
        if (plugin.shouldSuppressFlags(player)) return;
        if (!(event.getEntity() instanceof LivingEntity target)) return;
        // Only flag PvP scenarios (hitting other entities, not mobs)
        // Allow all LivingEntities for full kill-aura detection

        UUID uuid = player.getUniqueId();
        long now = System.currentTimeMillis();

        float currentYaw   = player.getLocation().getYaw();
        float currentPitch = player.getLocation().getPitch();
        float prevYaw      = lastYaw.getOrDefault(uuid, currentYaw);
        long  lastHit      = lastHitTime.getOrDefault(uuid, 0L);
        long  timeDiff     = now - lastHit;

        // --- Check A: Snap aim (huge yaw delta right before a hit) ---
        float yawDelta = Math.abs(currentYaw - prevYaw);
        if (yawDelta > 180) yawDelta = 360 - yawDelta;

        // Ping compensation: allow extra time for snap under lag
        int   ping      = plugin.getMetrics().pingMs(player);
        long  snapWindow = 80 + (ping > 0 ? Math.min(100L, ping / 3L) : 0L);
        if (yawDelta > 120 && timeDiff < snapWindow) {
            flag(player, "snap yaw=" + String.format("%.1f", yawDelta) + " t=" + timeDiff + "ms");
        }

        // --- Check B: Hitting entity that is NOT in front of the player ---
        double angle = getAngleTo(player, target);
        // 160° is very conservative — only catches blatant 180° behind-you hits
        if (angle > 160) {
            flag(player, "behind angle=" + String.format("%.1f", angle));
        }

        // --- Check C: Suspiciously low yaw-variance over last 25 hits ---
        List<Float> history = yawHistory.computeIfAbsent(uuid, k -> new ArrayList<>());
        history.add(yawDelta);
        if (history.size() > 25) history.remove(0);
        if (history.size() == 25) {
            double variance = computeVariance(history);
            double mean     = history.stream().mapToDouble(Float::doubleValue).average().orElse(0);
            // Real players show high variance; killaura produces near-zero variance
            if (variance < 0.15 && mean < 2.0) {
                flag(player, "low_yaw_var=" + String.format("%.4f", variance));
            }
        }

        // --- Check D: Multi-target aura (hitting 3+ distinct entities within 500ms) ---
        long windowMs = 500L + (ping > 0 ? Math.min(200L, ping) : 0L);
        long wStart   = targetWindowStart.getOrDefault(uuid, now);
        if (now - wStart > windowMs) {
            recentTargets.remove(uuid);
            targetWindowStart.put(uuid, now);
        }
        Set<UUID> targets = recentTargets.computeIfAbsent(uuid, k -> new HashSet<>());
        targets.add(target.getUniqueId());
        if (targets.size() >= 4) {
            flag(player, "multi_target=" + targets.size() + " in " + (now - wStart) + "ms");
            recentTargets.remove(uuid);
            targetWindowStart.put(uuid, now);
        }

        lastYaw.put(uuid, currentYaw);
        lastPitch.put(uuid, currentPitch);
        lastHitTime.put(uuid, now);
    }

    private static double getAngleTo(Player player, Entity target) {
        var dir      = player.getLocation().getDirection().normalize();
        var toTarget = target.getLocation().toVector()
                .add(new org.bukkit.util.Vector(0, 0.9, 0)) // aim at torso
                .subtract(player.getEyeLocation().toVector()).normalize();
        double dot = dir.dot(toTarget);
        return Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, dot))));
    }

    private static double computeVariance(List<Float> values) {
        double mean = values.stream().mapToDouble(Float::doubleValue).average().orElse(0);
        return values.stream().mapToDouble(v -> Math.pow(v - mean, 2)).average().orElse(0);
    }
}
