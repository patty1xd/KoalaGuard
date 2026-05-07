package com.koalaguard.checks.movement;

import com.koalaguard.KoalaGuard;
import com.koalaguard.checks.Check;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.potion.PotionEffectType;

public class SpeedCheck extends Check {

    private static final double MAX_HORIZONTAL_SPEED = 0.45; // blocks per tick

    public SpeedCheck(KoalaGuard plugin) {
        super(plugin, "speed");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (!isEnabled()) return;
        Player player = event.getPlayer();
        if (player.hasPermission("koalaguard.bypass")) return;
        if (player.isFlying() || player.isGliding()) return;
        if (player.isInsideVehicle()) return;

        double dx = event.getTo().getX() - event.getFrom().getX();
        double dz = event.getTo().getZ() - event.getFrom().getZ();
        double horizontal = Math.sqrt(dx * dx + dz * dz);

        double maxSpeed = MAX_HORIZONTAL_SPEED;

        // Allow speed potion
        if (player.hasPotionEffect(PotionEffectType.SPEED)) {
            int amp = player.getPotionEffect(PotionEffectType.SPEED).getAmplifier();
            maxSpeed += 0.1 * (amp + 1);
        }

        // Allow sprinting bonus
        if (player.isSprinting()) maxSpeed += 0.1;

        if (horizontal > maxSpeed * 1.4) {
            flag(player, String.format("h=%.3f max=%.3f", horizontal, maxSpeed));
        }
    }
}
