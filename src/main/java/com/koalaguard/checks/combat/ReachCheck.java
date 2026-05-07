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
        if (isExempt(player)) return;
        if (plugin.shouldSuppressFlags(player)) return;

        double maxReach = plugin.getConfig().getDouble("checks.reach.max-reach", 3.2);
        // Add ping compensation buffer
        int ping = plugin.getMetrics().pingMs(player);
        double buffer = 0.35 + (ping > 0 ? Math.min(0.65, ping / 300.0) : 0.0);

        double dist = player.getLocation().distance(event.getEntity().getLocation());
        if (dist > maxReach + buffer) {
            flag(player, "dist=" + String.format("%.2f", dist) + " max=" + maxReach);
        }
    }
}
