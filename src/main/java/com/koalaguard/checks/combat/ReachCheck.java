package com.koalaguard.checks.combat;

import com.koalaguard.KoalaGuard;
import com.koalaguard.checks.Check;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.entity.EntityDamageByEntityEvent;

public class ReachCheck extends Check {

    public ReachCheck(KoalaGuard plugin) { super(plugin, "reach"); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        if (!isEnabled()) return;
        if (!(event.getDamager() instanceof Player player)) return;
        if (player.hasPermission("koalaguard.bypass")) return;

        double maxReach = plugin.getConfig().getDouble("checks.reach.max-reach", 3.2);
        // Add ping compensation buffer
        double buffer = 0.5;

        double dist = player.getLocation().distance(event.getEntity().getLocation());
        if (dist > maxReach + buffer) {
            flag(player, "dist=" + String.format("%.2f", dist) + " max=" + maxReach);
        }
    }
}
