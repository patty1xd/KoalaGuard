package com.koalaguard.checks.movement;

import com.koalaguard.KoalaGuard;
import com.koalaguard.checks.Check;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.BlockPlaceEvent;

public class BurrowCheck extends Check {
    public BurrowCheck(KoalaGuard plugin) { super(plugin, "burrow"); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        if (!isEnabled()) return;
        Player player = event.getPlayer();
        if (isExempt(player)) return;
        if (plugin.shouldSuppressFlags(player)) return;

        // Burrow = placing a block at the same location as the player (inside themselves)
        double px = player.getLocation().getX();
        double py = player.getLocation().getY();
        double pz = player.getLocation().getZ();
        double bx = event.getBlockPlaced().getX() + 0.5;
        double by = event.getBlockPlaced().getY();
        double bz = event.getBlockPlaced().getZ() + 0.5;

        if (Math.abs(px - bx) < 0.5 && Math.abs(pz - bz) < 0.5 && (py >= by && py <= by + 2)) {
            flag(player, "placed_inside_self");
        }
    }
}
