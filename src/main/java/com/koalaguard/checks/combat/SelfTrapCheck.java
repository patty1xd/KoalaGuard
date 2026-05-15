package com.koalaguard.checks.combat;

import com.koalaguard.KoalaGuard;
import com.koalaguard.checks.Check;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.BlockPlaceEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Detects Meteor SelfTrap.
 *
 * From Meteor's source (SelfTrap.java):
 *   - Places obsidian blocks above the player's head to create a ceiling,
 *     trapping enemies or sealing the player inside a box.
 *   - Positions: (X+1,Y+2,Z), (X-1,Y+2,Z), (X,Y+2,Z+1), (X,Y+2,Z-1),
 *     and sometimes (X,Y+2,Z) directly above head.
 *   - Default delay: 0 ticks — all blocks placed within a single tick (~50 ms).
 *   - Server-side: multiple BlockPlaceEvents directly above player within
 *     50–100 ms, all within 1.5 blocks XZ of the player.
 *
 * Detection:
 *   Count blocks placed at Y+1 to Y+3 above the player, within 1.5 blocks XZ,
 *   within a 120 ms burst window.
 *   3+ such blocks in one burst = automated SelfTrap.
 *   Require 2 consecutive bursts before flagging.
 */
public class SelfTrapCheck extends Check {

    private final Map<UUID, Long>    windowStart = new HashMap<>();
    private final Map<UUID, Integer> windowCount = new HashMap<>();
    private final Map<UUID, Integer> burstStreak = new HashMap<>();

    private static final long   BURST_WINDOW_MS = 120L;
    private static final int    BURST_THRESHOLD = 3;
    private static final double MAX_XZ_DIST     = 1.6;

    public SelfTrapCheck(KoalaGuard plugin) { super(plugin, "selftrap"); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        if (!isEnabled()) return;
        Player player = event.getPlayer();
        if (isExempt(player)) return;
        if (plugin.shouldSuppressFlags(player)) return;

        Block placed = event.getBlockPlaced();
        double px = player.getLocation().getX();
        double py = player.getLocation().getY();
        double pz = player.getLocation().getZ();
        double bx = placed.getX() + 0.5;
        double by = placed.getY();
        double bz = placed.getZ() + 0.5;

        double xzDist = Math.sqrt(Math.pow(px - bx, 2) + Math.pow(pz - bz, 2));
        if (xzDist > MAX_XZ_DIST) return;

        // Must be above head level (Y+1 to Y+3)
        if (by < py + 1.0 || by > py + 3.0) return;

        UUID uuid = player.getUniqueId();
        long now  = System.currentTimeMillis();
        long wStart = windowStart.getOrDefault(uuid, now);

        if (now - wStart > BURST_WINDOW_MS) {
            int prev = windowCount.getOrDefault(uuid, 0);
            if (prev >= BURST_THRESHOLD) {
                int streak = burstStreak.merge(uuid, 1, Integer::sum);
                if (streak >= 2) {
                    flag(player, "selftrap_burst blocks=" + prev + " streak=" + streak);
                    burstStreak.put(uuid, 0);
                }
            } else {
                burstStreak.put(uuid, 0);
            }
            windowStart.put(uuid, now);
            windowCount.put(uuid, 1);
        } else {
            windowCount.merge(uuid, 1, Integer::sum);
        }
    }
}
