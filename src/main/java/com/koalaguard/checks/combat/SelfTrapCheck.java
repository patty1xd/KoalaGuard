package com.koalaguard.checks.combat;

import com.koalaguard.KoalaGuard;
import com.koalaguard.checks.Check;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.BlockPlaceEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class SelfTrapCheck extends Check {

    private final Map<UUID, Integer> aboveHeadCount = new HashMap<>();
    private final Map<UUID, Long> lastPlace = new HashMap<>();

    public SelfTrapCheck(KoalaGuard plugin) { super(plugin, "selftrap"); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        if (!isEnabled()) return;
        Player player = event.getPlayer();
        if (player.hasPermission("koalaguard.bypass")) return;

        Block placed = event.getBlockPlaced();
        double playerY = player.getLocation().getY();
        double blockY = placed.getY();

        // SelfTrap = placing blocks above head rapidly (ceiling trap)
        if (blockY >= playerY + 1 && blockY <= playerY + 3) {
            double px = player.getLocation().getX();
            double pz = player.getLocation().getZ();
            double bx = placed.getX() + 0.5;
            double bz = placed.getZ() + 0.5;
            if (Math.abs(px - bx) < 1.0 && Math.abs(pz - bz) < 1.0) {
                UUID uuid = player.getUniqueId();
                long now = System.currentTimeMillis();
                long last = lastPlace.getOrDefault(uuid, 0L);
                if (now - last < 300) {
                    int count = aboveHeadCount.merge(uuid, 1, Integer::sum);
                    if (count >= 3) {
                        flag(player, "above_head_blocks=" + count);
                        aboveHeadCount.put(uuid, 0);
                    }
                } else {
                    aboveHeadCount.put(uuid, 1);
                }
                lastPlace.put(uuid, now);
            }
        }
    }
}
