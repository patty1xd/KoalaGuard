package com.koalaguard.checks.world;

import com.koalaguard.KoalaGuard;
import com.koalaguard.checks.Check;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.BlockBreakEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class NukerCheck extends Check {

    private final Map<UUID, Integer> breakCount = new HashMap<>();
    private final Map<UUID, Long> windowStart = new HashMap<>();

    public NukerCheck(KoalaGuard plugin) { super(plugin, "nuker"); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (!isEnabled()) return;
        Player player = event.getPlayer();
        if (player.hasPermission("koalaguard.bypass")) return;
        if (player.getGameMode() == GameMode.CREATIVE) return;

        UUID uuid = player.getUniqueId();
        long now = System.currentTimeMillis();

        if (!windowStart.containsKey(uuid) || now - windowStart.get(uuid) > 1000) {
            windowStart.put(uuid, now);
            breakCount.put(uuid, 1);
            return;
        }

        int count = breakCount.merge(uuid, 1, Integer::sum);

        // Check distance from player to broken block
        Location playerLoc = player.getLocation();
        Location blockLoc = event.getBlock().getLocation();
        double dist = playerLoc.distance(blockLoc);

        // Nuker: breaking blocks out of arm's reach, or breaking too many in 1 second
        if (dist > 6.0) {
            flag(player, "break_dist=" + String.format("%.2f", dist));
        }

        // Breaking more than 10 blocks per second = nuker
        if (count > 10) {
            flag(player, "breaks_per_sec=" + count);
            breakCount.put(uuid, 0);
        }
    }
}
