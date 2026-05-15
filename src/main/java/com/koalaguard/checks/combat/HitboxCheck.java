package com.koalaguard.checks.combat;

import com.koalaguard.KoalaGuard;
import com.koalaguard.checks.Check;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.util.Vector;

import java.util.*;

/**
 * Detects Hitbox Expander / Hitbox Extender cheats.
 *
 * How the cheat works:
 *   The client inflates enemy hitboxes (e.g. 1.5x–3x their real size).
 *   This lets the player click on targets that are at wider angles from their
 *   crosshair and at slightly longer distances than vanilla allows.
 *
 * What the server sees:
 *   - Hits land at the very edge of or just beyond the normal reach envelope.
 *   - The player consistently hits targets that are significantly off-center
 *     from their look direction (large horizontal angle), but WITHOUT the
 *     sudden snap rotation that KillAura shows.
 *   - The average hit angle is higher than a legitimate player's, and the
 *     distribution is suspicious (low variance = machine-like consistency).
 *
 * Sub-checks:
 *   A) Average hit distance skewed toward the far edge of reach (> far-threshold).
 *   B) Mean horizontal hit angle consistently high with low variance
 *      (player hits off-center but always at a similar "comfortable" offset).
 */
public class HitboxCheck extends Check {

    // Per-player rolling buffers
    private final Map<UUID, List<Double>> hitDistances = new HashMap<>();
    private final Map<UUID, List<Double>> hitAngles    = new HashMap<>();
    private final Map<UUID, Float>        lastYaw      = new HashMap<>();

    // Window size for statistical analysis
    private static final int SAMPLE_SIZE = 20;

    public HitboxCheck(KoalaGuard plugin) { super(plugin, "hitbox"); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        if (!isEnabled()) return;
        if (!(event.getDamager() instanceof Player player)) return;
        if (!(event.getEntity() instanceof LivingEntity target)) return;
        if (isExempt(player)) return;
        if (plugin.shouldSuppressFlags(player)) return;

        UUID uuid = player.getUniqueId();
        int  ping = plugin.getMetrics().pingMs(player);

        // Eye-to-hitbox-center distance
        double dist = player.getEyeLocation().distance(
                target.getLocation().add(0, target.getHeight() / 2.0, 0));

        // Horizontal angle between player look direction and vector to target
        double angle = getHorizontalAngleTo(player, target);

        // If this hit would also be flagged by KillAura (very large snap) skip it —
        // avoid double-counting the same event.
        float  currYaw  = player.getLocation().getYaw();
        float  prevYaw  = lastYaw.getOrDefault(uuid, currYaw);
        float  yawDelta = Math.abs(currYaw - prevYaw);
        if (yawDelta > 180) yawDelta = 360 - yawDelta;
        lastYaw.put(uuid, currYaw);
        if (yawDelta > 90) return; // snap → KillAura's domain

        // --- Sub-check A: consistently hitting at far reach ---
        // Normal PvP: most hits happen at 1.5–2.5 blocks.
        // Hitbox expand: average shifts toward 3.0–4.5+ blocks.
        double maxLegitReach = plugin.getConfig().getDouble("checks.reach.max-reach", 3.2)
                + 0.4 + (ping > 0 ? Math.min(0.8, ping / 200.0) : 0);

        List<Double> dists = hitDistances.computeIfAbsent(uuid, k -> new ArrayList<>());
        dists.add(dist);
        if (dists.size() > SAMPLE_SIZE) dists.remove(0);

        if (dists.size() == SAMPLE_SIZE) {
            double avgDist = dists.stream().mapToDouble(Double::doubleValue).average().orElse(0);
            // Far-reach threshold: average hit distance > 88% of max legit reach
            if (avgDist > maxLegitReach * 0.88) {
                flag(player, String.format("far_reach avg=%.2f max=%.2f", avgDist, maxLegitReach));
                dists.clear();
            }
        }

        // --- Sub-check B: consistent wide-angle hits without snapping ---
        // Normal players: mean ~20-35°, high variance.
        // Hitbox expand: mean 45-80°, lower variance (always clicking the inflated edge).
        List<Double> angles = hitAngles.computeIfAbsent(uuid, k -> new ArrayList<>());
        angles.add(angle);
        if (angles.size() > SAMPLE_SIZE) angles.remove(0);

        if (angles.size() == SAMPLE_SIZE) {
            double mean     = angles.stream().mapToDouble(Double::doubleValue).average().orElse(0);
            double variance = computeVariance(angles, mean);

            // Signature: mean angle 45°–110° with suspiciously low variance
            // (below 140° to avoid overlapping with KillAura's 160° threshold)
            if (mean > 45 && mean < 140 && variance < 350) {
                flag(player, String.format("wide_angle mean=%.1f° var=%.1f", mean, variance));
                angles.clear();
            }
        }
    }

    /** Horizontal-plane angle between player's look direction and the vector to the target. */
    private static double getHorizontalAngleTo(Player player, LivingEntity target) {
        // Flatten to horizontal plane only — vertical offset shouldn't count
        Vector look = player.getLocation().getDirection();
        look.setY(0).normalize();

        Vector toTarget = target.getLocation().toVector()
                .subtract(player.getLocation().toVector());
        toTarget.setY(0);
        if (toTarget.lengthSquared() < 1e-6) return 0;
        toTarget.normalize();

        double dot = Math.max(-1, Math.min(1, look.dot(toTarget)));
        return Math.toDegrees(Math.acos(dot));
    }

    private static double computeVariance(List<Double> values, double mean) {
        return values.stream().mapToDouble(v -> Math.pow(v - mean, 2)).average().orElse(0);
    }
}
