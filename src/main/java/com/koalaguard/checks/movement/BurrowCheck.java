package com.koalaguard.checks.movement;

import com.koalaguard.KoalaGuard;
import com.koalaguard.check.CheckCategory;
import com.koalaguard.check.ListenerCheck;
import com.koalaguard.data.PlayerData;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.BlockPlaceEvent;

/**
 * Burrow detection — placing a solid block inside the player's own feet
 * position to phase underground. Physically impossible legitimately, so a
 * single confident occurrence flags.
 */
public final class BurrowCheck extends ListenerCheck {

    public BurrowCheck(KoalaGuard plugin) {
        super(plugin, "burrow", CheckCategory.MOVEMENT, "Placing a block inside one's own hitbox");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        if (!isEnabled()) return;
        Player player = event.getPlayer();
        if (isExempt(player) || player.isFlying() || player.getAllowFlight()) return;
        PlayerData data = plugin.getDataManager().get(player);
        if (data == null) return;

        Block placed = event.getBlockPlaced();
        if (!placed.getType().isSolid()) return;

        double px = player.getLocation().getX();
        double py = player.getLocation().getY();
        double pz = player.getLocation().getZ();

        double xz = Math.hypot(px - (placed.getX() + 0.5), pz - (placed.getZ() + 0.5));
        if (xz > 0.5) return;
        if (placed.getY() != (int) Math.floor(py)) return;

        fail(data, player, "block=" + placed.getType()
                + " at " + placed.getX() + "," + placed.getY() + "," + placed.getZ());
    }
}
