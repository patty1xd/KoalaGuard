package com.koalaguard.checks.movement;

import com.koalaguard.KoalaGuard;
import com.koalaguard.checks.Check;
import org.bukkit.GameMode;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.PlayerMoveEvent;

public class StepCheck extends Check {
    public StepCheck(KoalaGuard plugin) { super(plugin, "step"); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (!isEnabled()) return;
        Player player = event.getPlayer();
        if (player.hasPermission("koalaguard.bypass")) return;
        if (player.getGameMode() == GameMode.CREATIVE || player.getGameMode() == GameMode.SPECTATOR) return;
        if (player.isFlying() || player.isGliding()) return;

        double dy = event.getTo().getY() - event.getFrom().getY();
        // Normal step height is 0.6, allow 0.625 for tolerance
        if (dy > 0.625 && dy < 2.0 && player.isOnGround()) {
            Block below = event.getTo().getBlock().getRelative(0, -1, 0);
            if (below.getType().isSolid()) {
                flag(player, "step=" + String.format("%.3f", dy));
            }
        }
    }
}


