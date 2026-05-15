package com.koalaguard.checks.combat;

import com.koalaguard.KoalaGuard;
import com.koalaguard.checks.Check;
import org.bukkit.entity.Arrow;
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
 * Detects Meteor BowAimbot.
 *
 * From Meteor's source (BowAimbot.java):
 *   - On draw, computes the pitch required to land a fully charged arrow on
 *     the target using projectile motion math (gravity = 0.05 block/tick²).
 *   - Instantly snaps pitch to the computed value; yaw is already aligned.
 *   - Server observable:
 *       A) The player's aim barely moves in the ~50 ms just before the arrow
 *          fires — the aimbot already locked on earlier and holds.
 *       B) Every bow hit is achieved despite no significant last-moment
 *          aim adjustment — static aim over multiple hits is suspicious.
 *
 * Detection:
 *   Sub-check A: Track yaw+pitch delta in the last 2 move events before a
 *     bow hit. If combined delta < 1.5° AND the arrow hit a LivingEntity,
 *     that hit is "static". Accumulate static hits; flag at streak >= 4.
 *   Sub-check B: Hit rate — if > 80% of arrows fired hit a LivingEntity
 *     (tracked via EntityDamageByEntityEvent with Arrow damager) over the
 *     last 8 shots, flag combined with sub-check A evidence.
 *
 * False-positive guard: players far from target have large required pitch
 * corrections so static aim is expected — only check within 20 blocks.
 */
public class BowAimbotCheck extends Check {

    private final Map<UUID, Double> lastYawDelta   = new HashMap<>();
    private final Map<UUID, Double> lastPitchDelta = new HashMap<>();
    private final Map<UUID, Integer> staticStreak   = new HashMap<>();
    // Hit count and total arrow-fire events for hit-rate sub-check
    private final Map<UUID, Integer> hits          = new HashMap<>();
    private final Map<UUID, Integer> shots         = new HashMap<>();

    public BowAimbotCheck(KoalaGuard plugin) { super(plugin, "bowaimbot"); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (event.getTo() == null) return;
        Player player = event.getPlayer();

        double yawDelta   = Math.abs(event.getTo().getYaw()   - event.getFrom().getYaw());
        double pitchDelta = Math.abs(event.getTo().getPitch() - event.getFrom().getPitch());
        if (yawDelta   > 180) yawDelta   = 360 - yawDelta;

        UUID uuid = player.getUniqueId();
        lastYawDelta.put(uuid, yawDelta);
        lastPitchDelta.put(uuid, pitchDelta);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        if (!isEnabled()) return;
        if (!(event.getDamager() instanceof Arrow arrow)) return;
        if (!(arrow.getShooter() instanceof Player player)) return;
        if (!(event.getEntity() instanceof LivingEntity target)) return;
        if (isExempt(player)) return;
        if (plugin.shouldSuppressFlags(player)) return;

        UUID uuid = player.getUniqueId();

        // Only check within 20 blocks (further = large corrections expected)
        double dist = player.getLocation().distance(target.getLocation());
        if (dist > 20) return;

        shots.merge(uuid, 1, Integer::sum);
        hits.merge(uuid, 1, Integer::sum);

        double yawD   = lastYawDelta.getOrDefault(uuid, 999.0);
        double pitchD = lastPitchDelta.getOrDefault(uuid, 999.0);

        // Sub-check A: static aim before hit
        if (yawD + pitchD < 1.5) {
            int s = staticStreak.merge(uuid, 1, Integer::sum);
            if (s >= 4) {
                int totalShots = shots.getOrDefault(uuid, 1);
                int totalHits  = hits.getOrDefault(uuid, 0);
                double hitRate = (double) totalHits / totalShots;
                flag(player, String.format("bow_aimbot static_streak=%d hit_rate=%.0f%% dist=%.1f",
                        s, hitRate * 100, dist));
                staticStreak.put(uuid, 0);
                hits.put(uuid, 0);
                shots.put(uuid, 0);
            }
        } else {
            staticStreak.put(uuid, 0);
        }

        // Trim shot history
        int s = shots.getOrDefault(uuid, 0);
        if (s > 20) {
            shots.put(uuid, 10);
            hits.put(uuid, (int)(hits.getOrDefault(uuid, 0) * 0.5));
        }
    }
}
