package com.koalaguard.checks.combat;

import com.koalaguard.KoalaGuard;
import com.koalaguard.checks.Check;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.entity.EntityDamageByEntityEvent;

import java.util.*;

/**
 * Detects Meteor AutoClicker.
 *
 * From Meteor's source (AutoClicker.java):
 *   - Default delay: 2 ticks between clicks = ~100 ms = ~10 CPS.
 *   - Minimum delay: 0 ticks = theoretically 20 CPS (one per tick).
 *   - In "Hold" mode: sends clicks at the configured tick rate continuously.
 *
 * Observable signatures:
 *   A) CPS above human maximum (~20 CPS hard cap, flagged at max-cps).
 *   B) Click interval variance far too low — AutoClicker fires at a fixed
 *      tick rate, producing almost perfectly regular intervals.
 *      Meteor at 2-tick delay = intervals of ~100 ms with near-zero jitter.
 *      Human clicking always has variance > 400 ms² even at consistent pace.
 */
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
        long now  = System.currentTimeMillis();

        List<Long> times = clickTimes.computeIfAbsent(uuid, k -> new ArrayList<>());
        times.add(now);
        times.removeIf(t -> now - t > 1000);

        int cps    = times.size();
        int maxCps = plugin.getConfig().getInt("checks.autoclicker.max-cps", 20);

        // ── Check A: CPS too high ──────────────────────────────────────────
        if (cps > maxCps) {
            flag(player, "cps=" + cps + " max=" + maxCps);
            return;
        }

        // ── Check B: Interval variance too low (machine-like precision) ────
        // Require at least 10 samples and CPS >= 8 to avoid flagging slow clickers
        if (times.size() >= 10 && cps >= 8) {
            List<Long> intervals = new ArrayList<>();
            for (int i = 1; i < times.size(); i++) {
                intervals.add(times.get(i) - times.get(i - 1));
            }

            double mean = intervals.stream().mapToLong(Long::longValue).average().orElse(0);
            double variance = intervals.stream()
                    .mapToDouble(v -> Math.pow(v - mean, 2)).average().orElse(0);

            // Human variance at high CPS: typically 400–5000 ms².
            // Meteor AutoClicker at 2-tick delay: variance < 50 ms².
            // We use 80 as the threshold to be safe.
            if (variance < 80) {
                flag(player, String.format("machine_clicks cps=%d var=%.1f mean=%.1fms",
                        cps, variance, mean));
                times.clear();
            }
        }
    }
}
