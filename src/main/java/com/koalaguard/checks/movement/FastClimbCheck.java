package com.koalaguard.checks.movement;

import com.koalaguard.KoalaGuard;
import com.koalaguard.checks.Check;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.PlayerMoveEvent;

public class FastClimbCheck extends Check {
    private static final double MAX_CLIMB_SPEED = 0.2; // blocks per tick on ladder/vine

    public FastClimbCheck(KoalaGuard plugin) { super(plugin, "fastclimb"); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (!isEnabled()) return;
        Player player = event.getPlayer();
        if (player.hasPermission("koalaguard.bypass")) return;
        if (player.getGameMode() == GameMode.CREATIVE || player.getGameMode() == GameMode.SPECTATOR) return;

        Material block = player.getLocation().getBlock().getType();
        boolean onClimbable = block == Material.LADDER || block == Material.VINE
                || block == Material.SCAFFOLDING || block == Material.TWISTING_VINES
                || block == Material.WEEPING_VINES;

        if (!onClimbable) return;

        double dy = Math.abs(event.getTo().getY() - event.getFrom().getY());
        if (dy > MAX_CLIMB_SPEED * 1.5) {
            flag(player, "dy=" + String.format("%.3f", dy));
        }
    }
}
