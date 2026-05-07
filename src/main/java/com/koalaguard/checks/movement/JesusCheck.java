package com.koalaguard.checks.movement;

import com.koalaguard.KoalaGuard;
import com.koalaguard.checks.Check;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.PlayerMoveEvent;

public class JesusCheck extends Check {
    public JesusCheck(KoalaGuard plugin) { super(plugin, "jesus"); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (!isEnabled()) return;
        Player player = event.getPlayer();
        if (isExempt(player)) return;
        if (plugin.shouldSuppressFlags(player)) return;
        if (event.getTo() == null) return;

        Block block = event.getTo().getBlock();
        Material mat = block.getType();
        // conservative: only flag if player is on "ground" on liquid AND not moving vertically
        if ((mat == Material.WATER || mat == Material.LAVA) && player.isOnGround()) {
            double dy = event.getTo().getY() - event.getFrom().getY();
            if (Math.abs(dy) < 0.01) {
                flag(player, "walking on " + mat.name());
            }
        }
    }
}
