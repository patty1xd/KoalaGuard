package com.koalaguard.checks.combat;

import com.koalaguard.KoalaGuard;
import com.koalaguard.checks.Check;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.BlockPlaceEvent;

import java.util.*;

/**
 * Detects Meteor Surround.
 *
 * From Meteor's source (Surround.java):
 *   - Places obsidian/blocks in the 4 cardinal directions at the player's
 *     feet level (Y = playerY - 1) to create a protective barrier.
 *   - Default delay: 0 ticks — all 4 placements fire in a single server tick.
 *   - Positions: (X+1,Y,Z), (X-1,Y,Z), (X,Y,Z+1), (X,Y,Z-1) relative to
 *     the player's block position.
 *   - Server-side: 4 BlockPlaceEvents within ~50 ms at player-adjacent positions.
 *
 * Detection:
 *   Count block placements within 1.5 blocks XZ AND at player Y ± 1 level
 *   within a 100 ms window. 4+ placements in that window = automated surround.
 *   Require 2 consecutive bursts before flagging to absorb lag spikes.
 */
public class SurroundCheck extends Check {

    private final Map<UUID, Long>    windowStart = new HashMap<>();
    private final Map<UUID, Integer> windowCount = new HashMap<>();
    private final Map<UUID, Integer> burstStreak = new HashMap<>();

    private static final long BURST_WINDOW_MS  = 100L;
    private static final int  BURST_THRESHOLD  = 4;
    private static final double MAX_XZ_DIST    = 1.6;

    public SurroundCheck(KoalaGuard plugin) { super(plugin, "surround"); }

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

        // Surround blocks sit at player's feet level (Y - 1) or same Y
        if (by < py - 1.5 || by > py + 1.0) return;

        UUID uuid = player.getUniqueId();
        long now  = System.currentTimeMillis();
        long wStart = windowStart.getOrDefault(uuid, now);

        if (now - wStart > BURST_WINDOW_MS) {
            int prev = windowCount.getOrDefault(uuid, 0);
            if (prev >= BURST_THRESHOLD) {
                int streak = burstStreak.merge(uuid, 1, Integer::sum);
                if (streak >= 2) {
                    flag(player, "surround_burst blocks=" + prev + " streak=" + streak);
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
