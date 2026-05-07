package com.koalaguard.checks.combat;

import com.koalaguard.KoalaGuard;
import com.koalaguard.checks.Check;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerMoveEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class BowAimbotCheck extends Check {

    private final Map<UUID, Double> lastYawChange = new HashMap<>();
    private final Map<UUID, Long> lastBowHit = new HashMap<>();

    public BowAimbotCheck(KoalaGuard plugin) { super(plugin, "bowaimbot"); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        if (!isEnabled()) return;
        if (!(event.getDamager() instanceof Arrow arrow)) return;
        if (!(arrow.getShooter() instanceof Player player)) return;
        if (player.hasPermission("koalaguard.bypass")) return;

        UUID uuid = player.getUniqueId();
        // If they barely moved their head just before the hit (snap aim)
        double yawDelta = lastYawChange.getOrDefault(uuid, 999.0);
        if (yawDelta < 0.1) {
            flag(player, "static_yaw_bow_hit yaw_delta=" + String.format("%.3f", yawDelta));
        }
        lastBowHit.put(uuid, System.currentTimeMillis());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        double yawDelta = Math.abs(event.getTo().getYaw() - event.getFrom().getYaw());
        lastYawChange.put(player.getUniqueId(), (double) yawDelta);
    }
}
