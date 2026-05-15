package com.koalaguard.checks.player;

import com.koalaguard.KoalaGuard;
import com.koalaguard.checks.Check;
import org.bukkit.entity.Player;
import org.bukkit.entity.ThrownExpBottle;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.entity.ProjectileLaunchEvent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Detects Meteor ExpThrower.
 *
 * From Meteor's source (ExpThrower.java):
 *   - Each tick, right-clicks a bottle-o'-enchanting to throw it.
 *   - Default delay: 0 ticks → throws every server tick = 1 throw per 50 ms.
 *   - Human throwing speed: limited by right-click rate (~8–10 /sec = 100–125 ms).
 *   - Machine rate: up to 20/sec (one per tick).
 *
 * Detection via ProjectileLaunchEvent for ThrownExpBottle:
 *   A) Throw interval < 55 ms (sub-tick throw rate), streak >= 4.
 *   B) Variance < 30 ms² over 8 throws with mean < 70 ms = machine timing.
 */
public class ExpThrowerCheck extends Check {

    private final Map<UUID, Long>        lastThrowMs  = new HashMap<>();
    private final Map<UUID, Integer>     fastStreak   = new HashMap<>();
    private final Map<UUID, List<Long>>  intervals    = new HashMap<>();

    private static final long FAST_THROW_MS = 55L;

    public ExpThrowerCheck(KoalaGuard plugin) { super(plugin, "expthrower"); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onProjectile(ProjectileLaunchEvent event) {
        if (!isEnabled()) return;
        if (!(event.getEntity() instanceof ThrownExpBottle bottle)) return;
        if (!(bottle.getShooter() instanceof Player player)) return;
        if (isExempt(player)) return;
        if (plugin.shouldSuppressFlags(player)) return;

        UUID uuid = player.getUniqueId();
        long now  = System.currentTimeMillis();
        Long last = lastThrowMs.put(uuid, now);

        if (last == null) return;
        long interval = now - last;

        // Sub-check A: raw speed
        if (interval < FAST_THROW_MS) {
            int s = fastStreak.merge(uuid, 1, Integer::sum);
            if (s >= 4) {
                flag(player, "rapid_exp_throw interval=" + interval + "ms streak=" + s);
                fastStreak.put(uuid, 0);
                intervals.remove(uuid);
                return;
            }
        } else {
            fastStreak.put(uuid, 0);
        }

        // Sub-check B: machine timing variance
        List<Long> ivs = intervals.computeIfAbsent(uuid, k -> new ArrayList<>());
        ivs.add(interval);
        if (ivs.size() > 10) ivs.remove(0);

        if (ivs.size() >= 8) {
            double mean = ivs.stream().mapToLong(Long::longValue).average().orElse(0);
            double var  = ivs.stream().mapToDouble(v -> Math.pow(v - mean, 2)).average().orElse(0);
            if (var < 30 && mean < 70) {
                flag(player, String.format("machine_throw mean=%.0fms var=%.1f", mean, var));
                ivs.clear();
            }
        }
    }
}
