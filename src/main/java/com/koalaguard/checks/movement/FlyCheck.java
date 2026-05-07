package com.koalaguard.checks.movement;

import com.koalaguard.KoalaGuard;
import com.koalaguard.checks.Check;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.potion.PotionEffectType;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class FlyCheck extends Check {

    private final Map<UUID, Integer> airTicks = new HashMap<>();

    public FlyCheck(KoalaGuard plugin) {
        super(plugin, "fly");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (!isEnabled()) return;
        Player player = event.getPlayer();
        if (isExempt(player)) return;
        if (plugin.shouldSuppressFlags(player)) return;
        if (player.getAllowFlight() || player.isFlying()) return;
        if (player.getGameMode() == GameMode.CREATIVE || player.getGameMode() == GameMode.SPECTATOR) return;
        if (player.isGliding() || player.isInsideVehicle()) return;
        if (event.getTo() == null) return;

        boolean onGround = event.getTo().getBlock().getRelative(0, -1, 0).getType().isSolid()
                || player.isOnGround();

        UUID uuid = player.getUniqueId();
        if (!onGround) {
            int ticks = airTicks.merge(uuid, 1, Integer::sum);
            double dy = event.getTo().getY() - event.getFrom().getY();

            // Conservative: only flag if sustained level/up movement for a while
            // and not explained by jump boost.
            if (ticks > 35 && dy >= 0.02 && !player.hasPotionEffect(PotionEffectType.JUMP_BOOST)) {
                flag(player, "airTicks=" + ticks + " dy=" + String.format("%.3f", dy));
            }
        } else {
            airTicks.remove(uuid);
        }
    }
}
