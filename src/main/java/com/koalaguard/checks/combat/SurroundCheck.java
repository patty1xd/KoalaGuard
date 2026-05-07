package com.koalaguard.checks.combat;

import com.koalaguard.KoalaGuard;
import com.koalaguard.checks.Check;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.BlockPlaceEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class SurroundCheck extends Check {

    private final Map<UUID, Long> lastPlace = new HashMap<>();
    private final Map<UUID, Integer> placeCount = new HashMap<>();

    public SurroundCheck(KoalaGuard plugin) { super(plugin, "surround"); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        if (!isEnabled()) return;
        Player player = event.getPlayer();
        if (player.hasPermission("koalaguard.bypass")) return;

        // Surround = rapidly placing blocks around player's feet
        Block placed = event.getBlockPlaced();
        double px = player.getLocation().getX();
        double pz = player.getLocation().getZ();
        double bx = placed.getX() + 0.5;
        double bz = placed.getZ() + 0.5;
        double dist = Math.sqrt(Math.pow(px - bx, 2) + Math.pow(pz - bz, 2));

        if (dist > 2.0) return; // Not near player

        UUID uuid = player.getUniqueId();
        long now = System.currentTimeMillis();
        long last = lastPlace.getOrDefault(uuid, 0L);

        if (now - last < 200) {
            int count = placeCount.merge(uuid, 1, Integer::sum);
            if (count >= 4) { // 4 blocks placed around self in <800ms
                flag(player, "surround_blocks=" + count);
                placeCount.put(uuid, 0);
            }
        } else {
            placeCount.put(uuid, 1);
        }
        lastPlace.put(uuid, now);
    }
}
