package com.koalaguard.checks.combat;

import com.koalaguard.KoalaGuard;
import com.koalaguard.checks.Check;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.entity.EntityDamageByEntityEvent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Detects Meteor Reach.
 *
 * From Meteor's source (Reach.java):
 *   - Extends the player's attack range beyond vanilla (3.0 survival, 4.5 creative).
 *   - Default extra reach: +2.5 blocks in combat, configurable.
 *   - Sends attacks at distances where the server would normally reject them.
 *
 * Server-side measurement:
 *   Eye position → hitbox center of target.
 *   Vanilla creative = 4.5, survival = 3.0 blocks.
 *   Ping buffer scales with latency to absorb movement prediction errors.
 *
 * Detection strategy (single-flag + average):
 *   A) Immediate flag if distance > max + pingBuffer (obviously impossible).
 *   B) Average distance over last 10 hits: if mean > legitimateMax + 0.5,
 *      the player is consistently hitting at extended range.
 *   Require streak >= 3 for sub-check A to avoid single-event FPs.
 */
public class ReachCheck extends Check {

    private final Map<UUID, Integer>   overReachStreak = new HashMap<>();
    private final Map<UUID, List<Double>> recentDists  = new HashMap<>();

    public ReachCheck(KoalaGuard plugin) { super(plugin, "reach"); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        if (!isEnabled()) return;
        if (!(event.getDamager() instanceof Player player)) return;
        if (!(event.getEntity() instanceof LivingEntity target)) return;
        if (isExempt(player)) return;
        if (plugin.shouldSuppressFlags(player)) return;

        double maxReach = plugin.getConfig().getDouble("checks.reach.max-reach", 3.2);
        int    ping     = plugin.getMetrics().pingMs(player);

        // Ping buffer: higher latency = more positional desync allowed
        // Formula: 0.3 base + up to 1.0 for 200ms ping
        double pingBuffer = 0.3 + (ping > 0 ? Math.min(1.0, ping / 200.0) : 0.0);

        // Eye → hitbox center distance
        double dist = player.getEyeLocation().distance(
                target.getLocation().add(0, target.getHeight() / 2.0, 0));

        UUID uuid = player.getUniqueId();

        // Sub-check A: single-event hard cap
        if (dist > maxReach + pingBuffer) {
            int s = overReachStreak.merge(uuid, 1, Integer::sum);
            if (s >= 3) {
                flag(player, String.format("reach dist=%.2f max=%.2f ping=%dms streak=%d",
                        dist, maxReach, ping, s));
                overReachStreak.put(uuid, 0);
            }
        } else {
            overReachStreak.put(uuid, 0);
        }

        // Sub-check B: rolling average over 10 hits
        List<Double> dists = recentDists.computeIfAbsent(uuid, k -> new ArrayList<>());
        dists.add(dist);
        if (dists.size() > 10) dists.remove(0);

        if (dists.size() == 10) {
            double mean = dists.stream().mapToDouble(Double::doubleValue).average().orElse(0);
            if (mean > maxReach + 0.5) {
                flag(player, String.format("avg_reach=%.2f max=%.2f over_10_hits", mean, maxReach));
                dists.clear();
            }
        }
    }
}
