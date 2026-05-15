package com.koalaguard.checks.combat;

import com.koalaguard.KoalaGuard;
import com.koalaguard.checks.Check;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Detects Meteor AimAssist.
 *
 * From Meteor's source (AimAssist.java):
 *   - Each tick, if an enemy is within range, the module rotates the player's
 *     yaw/pitch toward the enemy by a fixed "speed" value (default 5°/tick).
 *   - This produces smooth, consistent rotation increments that always move
 *     TOWARD the target — never overshooting or reversing.
 *   - Human aiming: erratic, overshoots, corrects back; large variance.
 *   - AimAssist: each delta step is nearly identical; extremely low variance.
 *
 * Detection:
 *   During a 2-second combat window, track the yaw delta direction relative
 *   to the target on each move packet.
 *   A) "Correction ratio": fraction of moves where yaw moved toward target.
 *      AimAssist: > 90% toward target. Human: ~55–70%.
 *   B) Yaw delta variance: AimAssist produces near-constant step sizes.
 *      Human: high variance. Meteor at speed=5: variance < 5 deg².
 *   Require both sub-checks triggered over 20 samples before flagging.
 */
public class AimAssistCheck extends Check {

    private static final int    SAMPLE_SIZE      = 20;
    private static final double CORRECTION_RATIO = 0.88;
    private static final double MAX_YAW_VAR      = 8.0;

    private final Map<UUID, Long>    lastHitMs    = new HashMap<>();
    private final Map<UUID, UUID>    lastTarget   = new HashMap<>();
    private final Map<UUID, Integer> towardCount  = new HashMap<>();
    private final Map<UUID, Integer> totalCount   = new HashMap<>();
    private final Map<UUID, Double>  prevYaw      = new HashMap<>();
    // Yaw deltas for variance
    private final Map<UUID, java.util.List<Double>> yawDeltas = new HashMap<>();

    public AimAssistCheck(KoalaGuard plugin) { super(plugin, "aimassist"); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player player)) return;
        if (!(event.getEntity() instanceof LivingEntity target)) return;
        if (isExempt(player)) return;

        UUID uuid = player.getUniqueId();
        lastHitMs.put(uuid, System.currentTimeMillis());
        lastTarget.put(uuid, target.getUniqueId());
        prevYaw.put(uuid, (double) player.getLocation().getYaw());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (!isEnabled()) return;
        Player player = event.getPlayer();
        if (isExempt(player)) return;
        if (plugin.shouldSuppressFlags(player)) return;
        if (event.getTo() == null) return;

        UUID uuid = player.getUniqueId();
        Long hitMs = lastHitMs.get(uuid);
        if (hitMs == null || System.currentTimeMillis() - hitMs > 2000) {
            resetPlayer(uuid);
            return;
        }

        UUID targetId = lastTarget.get(uuid);
        if (targetId == null) return;

        Entity targetEntity = plugin.getServer().getEntity(targetId);
        if (targetEntity == null || !targetEntity.isValid()) {
            resetPlayer(uuid);
            return;
        }

        double fromYaw = prevYaw.getOrDefault(uuid, (double) event.getFrom().getYaw());
        double toYaw   = event.getTo().getYaw();
        double delta   = toYaw - fromYaw;
        // Normalize to [-180, 180]
        while (delta > 180)  delta -= 360;
        while (delta < -180) delta += 360;

        if (Math.abs(delta) < 0.01) {
            prevYaw.put(uuid, toYaw);
            return; // no meaningful rotation
        }

        // Direction from player to target on horizontal plane
        Vector toTarget = targetEntity.getLocation().toVector()
                .subtract(player.getLocation().toVector());
        toTarget.setY(0);
        if (toTarget.lengthSquared() < 1e-6) {
            prevYaw.put(uuid, toYaw);
            return;
        }

        // Compute signed horizontal angle error (positive = need to turn right)
        Vector look = player.getLocation().getDirection();
        look.setY(0);
        look.normalize();
        toTarget.normalize();

        // Cross product Y gives sign of rotation needed
        double cross = look.getX() * toTarget.getZ() - look.getZ() * toTarget.getX();
        // positive cross → target is to the right → should turn right (positive yaw delta)
        boolean shouldTurnRight = cross > 0;
        boolean didTurnRight    = delta > 0;
        boolean movedToward     = (shouldTurnRight == didTurnRight);

        totalCount.merge(uuid, 1, Integer::sum);
        if (movedToward) towardCount.merge(uuid, 1, Integer::sum);

        java.util.List<Double> deltas = yawDeltas.computeIfAbsent(uuid, k -> new java.util.ArrayList<>());
        deltas.add(Math.abs(delta));
        if (deltas.size() > SAMPLE_SIZE) deltas.remove(0);

        int total   = totalCount.getOrDefault(uuid, 0);
        int toward  = towardCount.getOrDefault(uuid, 0);

        if (total >= SAMPLE_SIZE && deltas.size() == SAMPLE_SIZE) {
            double ratio = (double) toward / total;
            double mean  = deltas.stream().mapToDouble(Double::doubleValue).average().orElse(0);
            double var   = deltas.stream().mapToDouble(v -> Math.pow(v - mean, 2)).average().orElse(0);

            if (ratio >= CORRECTION_RATIO && var < MAX_YAW_VAR && mean > 0.1) {
                flag(player, String.format("aim_assist ratio=%.2f var=%.2f mean=%.2f°",
                        ratio, var, mean));
                resetPlayer(uuid);
            } else {
                // Slide window
                totalCount.put(uuid, total - 1);
                towardCount.put(uuid, Math.max(0, toward - (movedToward ? 0 : 1)));
            }
        }

        prevYaw.put(uuid, toYaw);
    }

    private void resetPlayer(UUID uuid) {
        lastHitMs.remove(uuid);
        lastTarget.remove(uuid);
        towardCount.remove(uuid);
        totalCount.remove(uuid);
        prevYaw.remove(uuid);
        yawDeltas.remove(uuid);
    }
}
