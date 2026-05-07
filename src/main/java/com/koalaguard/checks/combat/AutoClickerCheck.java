package com.koalaguard.checks.combat;

import com.koalaguard.KoalaGuard;
import com.koalaguard.checks.Check;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.entity.EntityDamageByEntityEvent;

import java.util.*;

public class AutoClickerCheck extends Check {

    private final Map<UUID, List<Long>> clickTimes = new HashMap<>();

    public AutoClickerCheck(KoalaGuard plugin) { super(plugin, "autoclicker"); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        if (!isEnabled()) return;
        if (!(event.getDamager() instanceof Player player)) return;
        if (isExempt(player)) return;
        if (plugin.shouldSuppressFlags(player)) return;

        UUID uuid = player.getUniqueId();
        long now = System.currentTimeMillis();

        List<Long> times = clickTimes.computeIfAbsent(uuid, k -> new ArrayList<>());
        times.add(now);
        // Keep only clicks in last second
        times.removeIf(t -> now - t > 1000);

        int cps = times.size();
        int maxCps = plugin.getConfig().getInt("checks.autoclicker.max-cps", 20);

        if (cps > maxCps) {
            flag(player, "cps=" + cps);
            return;
        }

        // Check for inhuman consistency (perfectly even intervals = macro/autoclicker)
        if (times.size() >= 10) {
            List<Long> intervals = new ArrayList<>();
            for (int i = 1; i < times.size(); i++) {
                intervals.add(times.get(i) - times.get(i - 1));
            }
            double mean = intervals.stream().mapToLong(Long::longValue).average().orElse(0);
            double variance = intervals.stream().mapToDouble(v -> Math.pow(v - mean, 2)).average().orElse(0);
            // Be conservative to avoid FPs: require higher cps and very low variance.
            if (variance < 25 && cps > 12) {
                flag(player, "low_click_variance=" + String.format("%.1f", variance) + " cps=" + cps);
            }
        }
    }
}
