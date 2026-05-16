package com.koalaguard.checks.player;

import com.koalaguard.KoalaGuard;
import com.koalaguard.check.CheckCategory;
import com.koalaguard.check.ListenerCheck;
import com.koalaguard.data.PlayerData;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;

/**
 * Block-reach detection (Meteor "Reach" applied to blocks / GhostHand).
 *
 * Survival block interaction is limited to ~4.5-5 blocks. Interacting with a
 * block well beyond that (eye → block centre) needs an extended-reach hack.
 * A latency buffer and a streak keep lag edge-cases from flagging.
 */
public final class BlockReachCheck extends ListenerCheck {

    public BlockReachCheck(KoalaGuard plugin) {
        super(plugin, "blockreach", CheckCategory.PLAYER, "Interacting with blocks beyond reach");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        check(event.getPlayer(), event.getBlock().getLocation());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        check(event.getPlayer(), event.getBlock().getLocation());
    }

    private void check(Player player, Location block) {
        if (!isEnabled()) return;
        if (isExempt(player) || player.getGameMode() == GameMode.CREATIVE) return;
        PlayerData data = plugin.getDataManager().get(player);
        if (data == null) return;

        int ping = plugin.getMetrics().pingMs(player);
        double limit = cfgD("max-reach", 5.2) + (ping > 0 ? Math.min(0.8, ping / 250.0) : 0);
        double dist = player.getEyeLocation().distance(block.clone().add(0.5, 0.5, 0.5));

        if (dist > limit) {
            int s = data.incInt(k("s"));
            if (s >= 3) {
                fail(data, player, String.format("block dist=%.2f limit=%.2f streak=%d",
                        dist, limit, s));
                data.setInt(k("s"), 0);
            }
        } else {
            data.addInt(k("s"), -1);
        }
    }
}
