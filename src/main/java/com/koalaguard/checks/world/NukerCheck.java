package com.koalaguard.checks.world;

import com.koalaguard.KoalaGuard;
import com.koalaguard.checks.Check;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.BlockBreakEvent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Detects Meteor Nuker.
 *
 * From Meteor's source (Nuker.java):
 *   - Breaks all blocks within a configurable sphere radius (default 4 blocks).
 *   - Bypasses the 1-block-per-tick server limit, sending multiple break
 *     packets per tick.
 *   - "Flat" mode: only breaks at Y level of the player.
 *   - Server-observable:
 *       A) Multiple blocks broken within a single tick (< 55 ms between breaks).
 *       B) Breaking blocks at > 4.5 block distance (Meteor extends reach).
 *       C) Break rate consistently > 8 blocks/sec.
 *
 * Detection:
 *   A) More than 8 block breaks per second.
 *   B) Break distance > 5.0 blocks (eye to block center, with 0.5 buffer).
 *   Both checks require survival mode and use a 1-second rolling window.
 */
public class NukerCheck extends Check {

    private final Map<UUID, List<Long>> breakTimes = new HashMap<>();

    private static final double MAX_BREAK_DIST   = 5.0;
    private static final int    MAX_BREAKS_PER_S = 8;

    public NukerCheck(KoalaGuard plugin) { super(plugin, "nuker"); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (!isEnabled()) return;
        Player player = event.getPlayer();
        if (isExempt(player)) return;
        if (plugin.shouldSuppressFlags(player)) return;
        if (player.getGameMode() == GameMode.CREATIVE) return;

        UUID uuid = player.getUniqueId();
        long now  = System.currentTimeMillis();

        // Sub-check B: distance (eye to block center)
        Location eye    = player.getEyeLocation();
        Location center = event.getBlock().getLocation().add(0.5, 0.5, 0.5);
        double dist = eye.distance(center);
        if (dist > MAX_BREAK_DIST) {
            flag(player, "nuker_dist=" + String.format("%.2f", dist) + " max=" + MAX_BREAK_DIST);
        }

        // Sub-check A: break rate
        List<Long> times = breakTimes.computeIfAbsent(uuid, k -> new ArrayList<>());
        times.add(now);
        times.removeIf(t -> now - t > 1000);

        if (times.size() > MAX_BREAKS_PER_S) {
            flag(player, "nuker_rate=" + times.size() + "/s");
            times.clear();
        }
    }
}
