package com.koalaguard.checks.player;

import com.koalaguard.KoalaGuard;
import com.koalaguard.check.CheckCategory;
import com.koalaguard.check.ListenerCheck;
import com.koalaguard.data.PlayerData;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.BlockPlaceEvent;

/**
 * AirPlace detection — a block placed against air with no solid neighbour
 * (the client faked a placement target). Liquids/waterlogging are excluded.
 */
public final class AirPlaceCheck extends ListenerCheck {

    public AirPlaceCheck(KoalaGuard plugin) {
        super(plugin, "airplace", CheckCategory.PLAYER, "Placing a block against air");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        if (!isEnabled()) return;
        Player player = event.getPlayer();
        if (isExempt(player)) return;
        PlayerData data = plugin.getDataManager().get(player);
        if (data == null) return;

        Material against = event.getBlockAgainst().getType();
        if (against == Material.WATER || against == Material.LAVA) return;
        if (against.isSolid()) return;

        Block placed = event.getBlockPlaced();
        for (BlockFace f : BlockFace.values()) {
            if (f == BlockFace.SELF) continue;
            if (placed.getRelative(f).getType().isSolid()) return;
        }
        fail(data, player, "placed=" + placed.getType() + " against=" + against
                + " @ " + placed.getX() + "," + placed.getY() + "," + placed.getZ());
    }
}
