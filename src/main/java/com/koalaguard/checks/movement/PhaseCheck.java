package com.koalaguard.checks.movement;

import com.koalaguard.KoalaGuard;
import com.koalaguard.checks.Check;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.PlayerMoveEvent;

public class PhaseCheck extends Check {
    public PhaseCheck(KoalaGuard plugin) { super(plugin, "phase"); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (!isEnabled()) return;
        Player player = event.getPlayer();
        if (isExempt(player)) return;
        if (plugin.shouldSuppressFlags(player)) return;
        if (player.isFlying()) return;

        Location to = event.getTo();
        if (to == null) return;
        Material feet = to.getBlock().getType();
        Material head = to.getBlock().getRelative(0, 1, 0).getType();

        // Player is inside solid blocks they shouldn't be able to enter
        // conservative: require both feet+head to be solid non-passables
        if (isPhaseBlock(feet) && isPhaseBlock(head)) {
            flag(player, "inside=" + feet.name());
        }
    }

    private boolean isPhaseBlock(Material mat) {
        return mat.isSolid() && !mat.isTransparent()
                && mat != Material.CHEST && mat != Material.TRAPPED_CHEST
                && mat != Material.ENDER_CHEST && mat != Material.BARREL
                && mat != Material.CRAFTING_TABLE && mat != Material.FURNACE;
    }
}
