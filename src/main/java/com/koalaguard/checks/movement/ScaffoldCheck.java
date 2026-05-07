package com.koalaguard.checks.movement;

import com.koalaguard.KoalaGuard;
import com.koalaguard.checks.Check;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.BlockPlaceEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class ScaffoldCheck extends Check {

    private final Map<UUID, Long> lastPlace = new HashMap<>();
    private final Map<UUID, Integer> placeCount = new HashMap<>();

    public ScaffoldCheck(KoalaGuard plugin) { super(plugin, "scaffold"); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        if (!isEnabled()) return;
        Player player = event.getPlayer();
        if (player.hasPermission("koalaguard.bypass")) return;

        // Scaffold = placing blocks under yourself while moving, very fast
        if (event.getBlockAgainst().getFace(event.getBlockPlaced()) != BlockFace.UP) return;

        UUID uuid = player.getUniqueId();
        long now = System.currentTimeMillis();
        long last = lastPlace.getOrDefault(uuid, 0L);

        if (now - last < 100) { // placed within 100ms
            int count = placeCount.merge(uuid, 1, Integer::sum);
            if (count >= 5) {
                // Check if player is moving while placing rapidly below themselves
                double playerY = player.getLocation().getY();
                double blockY = event.getBlockPlaced().getY();
                if (Math.abs(playerY - blockY - 1) < 0.5) {
                    flag(player, "rapid_below_place count=" + count);
                    placeCount.put(uuid, 0);
                }
            }
        } else {
            placeCount.put(uuid, 1);
        }
        lastPlace.put(uuid, now);
    }
}
