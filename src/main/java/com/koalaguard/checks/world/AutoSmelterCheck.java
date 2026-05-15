package com.koalaguard.checks.world;

import com.koalaguard.KoalaGuard;
import com.koalaguard.checks.Check;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.inventory.FurnaceExtractEvent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Detects Meteor AutoSmelter.
 *
 * From Meteor's source (AutoSmelter.java):
 *   - Automatically manages furnaces: puts fuel/items in, waits, takes output.
 *   - The extraction phase uses InvUtils.takeItems() which fires multiple
 *     FurnaceExtractEvents in rapid succession (< 50 ms per extract) when
 *     taking a full stack (64 items).
 *   - Human extraction: one click per item slot, limited to ~5–8 clicks/sec.
 *   - Machine extraction: all items taken within < 50 ms per FurnaceExtract.
 *
 * Detection:
 *   A) More than 5 FurnaceExtractEvents per second from the same player.
 *   B) Interval variance < 20 ms² over 6 extracts with mean < 40 ms =
 *      machine-timed extraction.
 */
public class AutoSmelterCheck extends Check {

    private final Map<UUID, List<Long>> extractTimes = new HashMap<>();

    private static final int  MAX_EXTRACTS_PER_S = 5;

    public AutoSmelterCheck(KoalaGuard plugin) { super(plugin, "autosmelter"); }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onExtract(FurnaceExtractEvent event) {
        if (!isEnabled()) return;
        Player player = event.getPlayer();
        if (isExempt(player)) return;
        if (plugin.shouldSuppressFlags(player)) return;

        UUID uuid = player.getUniqueId();
        long now  = System.currentTimeMillis();

        List<Long> times = extractTimes.computeIfAbsent(uuid, k -> new ArrayList<>());
        times.add(now);
        times.removeIf(t -> now - t > 1000);

        // Sub-check A: raw rate
        if (times.size() > MAX_EXTRACTS_PER_S) {
            flag(player, "extract_rate=" + times.size() + "/s");
            times.clear();
            return;
        }

        // Sub-check B: machine timing variance over 6 extracts
        if (times.size() >= 6) {
            List<Long> intervals = new ArrayList<>();
            for (int i = 1; i < times.size(); i++) {
                intervals.add(times.get(i) - times.get(i - 1));
            }
            double mean = intervals.stream().mapToLong(Long::longValue).average().orElse(0);
            double var  = intervals.stream().mapToDouble(v -> Math.pow(v - mean, 2)).average().orElse(0);
            if (var < 20 && mean < 40) {
                flag(player, String.format("machine_extract mean=%.0fms var=%.1f", mean, var));
                times.clear();
            }
        }
    }
}
