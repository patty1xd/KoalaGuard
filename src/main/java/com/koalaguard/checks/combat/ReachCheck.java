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

        // Ping-proportional buffer: higher ping = larger allowed window
        // This is the most important false-positive fix for reach.
        int ping = plugin.getMetrics().pingMs(player);
        double pingBuffer = 0.4 + (ping > 0 ? Math.min(1.0, ping / 200.0) : 0.0);

        // Measure eye-to-hitbox-center distance (more accurate than feet-to-feet)
        double dist = player.getEyeLocation().distance(
                event.getEntity().getLocation().add(0, event.getEntity().getHeight() / 2.0, 0)
        );

        if (dist > maxReach + pingBuffer) {
            flag(player, String.format("dist=%.2f max=%.2f ping=%dms", dist, maxReach, ping));
        }
    }
}
