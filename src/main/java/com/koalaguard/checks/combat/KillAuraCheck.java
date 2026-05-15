package com.koalaguard.checks.combat;

import com.koalaguard.KoalaGuard;
import com.koalaguard.checks.Check;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.util.Vector;

import java.util.*;

/**
 * Detects Meteor KillAura.
 *
 * From Meteor's source code:
 *   - KillAura attacks on TickEvent.Pre — every game tick, not on player clicks.
 *   - Default custom delay: 11 ticks (550 ms). Standard mode: full weapon cooldown.
 *   - Rotation mode "Always": rotates toward target body every tick.
 *   - Attacks are mechanically perfect — the player never misses the cooldown window.
 *   - Multi-target: can hit up to 5 targets per tick with maxTargets setting.
 *
 * Server-observable signatures:
 *   A) Attack timing consistency — real players have natural click variance;
 *      KillAura attacks at suspiciously uniform intervals (low variance).
 *   B) Multi-target hits — hitting 4+ distinct entities within 500 ms.
 *   C) Angle: target is significantly behind/off-center of the player.
 *   D) Snap rotation: huge yaw change immediately before a hit.
 */
public class KillAuraCheck extends Check {

    private final Map<UUID, Float>       lastYaw         = new HashMap<>();
    private final Map<UUID, Long>        lastHitMs       = new HashMap<>();
    private final Map<UUID, List<Long>>  hitIntervals    = new HashMap<>();

    // Multi-target tracking
    private final Map<UUID, Set<UUID>>   recentTargets   = new HashMap<>();
    private final Map<UUID, Long>        targetWindowStart = new HashMap<>();

    public KillAuraCheck(KoalaGuard plugin) { super(plugin, "killaura"); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        if (!isEnabled()) return;
        if (!(event.getDamager() instanceof Player player)) return;
        if (!(event.getEntity() instanceof LivingEntity target)) return;
        if (isExempt(player)) return;
        if (plugin.shouldSuppressFlags(player)) return;

        UUID uuid = player.getUniqueId();
        long now  = System.currentTimeMillis();
        int  ping = plugin.getMetrics().pingMs(player);

        float currYaw  = player.getLocation().getYaw();
        float prevYaw  = lastYaw.getOrDefault(uuid, currYaw);
        long  lastHit  = lastHitMs.getOrDefault(uuid, 0L);
        long  interval = now - lastHit;

        // ── Check A: Snap rotation ──────────────────────────────────────────
        float yawDelta = Math.abs(currYaw - prevYaw);
        if (yawDelta > 180) yawDelta = 360 - yawDelta;
        long snapWindow = 80 + (ping > 0 ? Math.min(100L, ping / 3L) : 0L);
        if (yawDelta > 120 && interval < snapWindow) {
            flag(player, "snap yaw=" + String.format("%.1f", yawDelta) + " t=" + interval + "ms");
        }

        // ── Check B: Attack interval consistency ───────────────────────────
        // Meteor's default: 11-tick hit delay = 550 ms, or vanilla cooldown ~600 ms.
        // Real players deviate by ±80–200 ms. KillAura deviates by <20 ms.
        if (interval > 100 && interval < 3000) { // only meaningful intervals
            List<Long> intervals = hitIntervals.computeIfAbsent(uuid, k -> new ArrayList<>());
            intervals.add(interval);
            if (intervals.size() > 15) intervals.remove(0);

            if (intervals.size() == 15) {
                double mean = intervals.stream().mapToLong(Long::longValue).average().orElse(0);
                double variance = intervals.stream()
                        .mapToDouble(v -> Math.pow(v - mean, 2)).average().orElse(0);
                // Real players: variance > 2000. KillAura: variance < 300.
                // Mean between 300–700 ms = weapon cooldown zone.
                if (variance < 300 && mean > 300 && mean < 800) {
                    flag(player, String.format("consistent_timing mean=%.0fms var=%.0f", mean, variance));
                    intervals.clear();
                }
            }
        }

        // ── Check C: Target behind / off-center ────────────────────────────
        double angle = horizontalAngleTo(player, target);
        if (angle > 155) {
            flag(player, "behind angle=" + String.format("%.1f", angle));
        }

        // ── Check D: Multi-target (maxTargets > 1 in Meteor) ───────────────
        long windowMs = 500L + (ping > 0 ? Math.min(200L, ping) : 0L);
        long wStart   = targetWindowStart.getOrDefault(uuid, now);
        if (now - wStart > windowMs) {
            recentTargets.remove(uuid);
            targetWindowStart.put(uuid, now);
        }
        recentTargets.computeIfAbsent(uuid, k -> new HashSet<>()).add(target.getUniqueId());
        if (recentTargets.get(uuid).size() >= 4) {
            flag(player, "multi_target=" + recentTargets.get(uuid).size());
            recentTargets.remove(uuid);
            targetWindowStart.put(uuid, now);
        }

        lastYaw.put(uuid, currYaw);
        lastHitMs.put(uuid, now);
    }

    private static double horizontalAngleTo(Player player, Entity target) {
        Vector look = player.getLocation().getDirection();
        look.setY(0);
        if (look.lengthSquared() < 1e-6) return 0;
        look.normalize();

        Vector toTarget = target.getLocation().toVector()
                .subtract(player.getLocation().toVector());
        toTarget.setY(0);
        if (toTarget.lengthSquared() < 1e-6) return 0;
        toTarget.normalize();

        double dot = Math.max(-1, Math.min(1, look.dot(toTarget)));
        return Math.toDegrees(Math.acos(dot));
    }
}
