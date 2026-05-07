package com.koalaguard.checks.movement;

import com.koalaguard.KoalaGuard;
import com.koalaguard.checks.Check;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerMoveEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class NoFallCheck extends Check {

    private final Map<UUID, Double> lastY = new HashMap<>();

    public NoFallCheck(KoalaGuard plugin) {
        super(plugin, "nofall");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        if (!isEnabled()) return;
        if (player.hasPermission("koalaguard.bypass")) return;
        if (player.getGameMode() == GameMode.CREATIVE || player.getGameMode() == GameMode.SPECTATOR) return;
        if (player.getAllowFlight() || player.isGliding()) return;

        UUID uuid = player.getUniqueId();
        double prevY = lastY.getOrDefault(uuid, event.getTo().getY());
        double currY = event.getTo().getY();
        lastY.put(uuid, currY);

        double fallen = prevY - currY;
        // Player fell more than 3 blocks in one tick (impossible without cheats)
        if (fallen > 3.5 && player.getFallDistance() < 1.0) {
            flag(player, "fell=" + String.format("%.2f", fallen) + " fallDist=" + player.getFallDistance());
        }
    }

    @EventHandler
    public void onDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        if (event.getCause() != EntityDamageEvent.DamageCause.FALL) return;
        lastY.remove(player.getUniqueId());
    }
}
