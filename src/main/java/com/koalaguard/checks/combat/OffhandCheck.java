package com.koalaguard.checks.combat;

import com.koalaguard.KoalaGuard;
import com.koalaguard.checks.Check;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Detects Meteor InvManager rapid offhand switching.
 *
 * From Meteor's source (InvManager.java / OffhandManager):
 *   - Manages the offhand slot automatically (e.g., keeps totem/crystal/gapple).
 *   - Uses the F-key swap packet (ServerboundPlayerActionPacket SWAP_ITEM_WITH_OFFHAND)
 *     or direct slot manipulation — fires PlayerSwapHandItemsEvent.
 *   - When managing offhand rapidly (e.g., crystal PvP + totem), can swap
 *     multiple times per second.
 *
 * Human baseline: F-key pressing cannot exceed ~8 swaps/sec.
 *   Meteor InvManager can fire > 10 swaps/sec with 0-tick delay.
 *
 * Detection:
 *   More than 10 PlayerSwapHandItemsEvents in 1 second = automated.
 *   Interval variance < 20 ms² over 10 swaps = machine timing.
 */
public class OffhandCheck extends Check {

    private final Map<UUID, List<Long>> swapTimes = new HashMap<>();

    private static final int  MAX_SWAPS_PER_SEC = 10;
    private static final long WINDOW_MS         = 1000L;

    public OffhandCheck(KoalaGuard plugin) { super(plugin, "offhand"); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSwap(PlayerSwapHandItemsEvent event) {
        if (!isEnabled()) return;
        Player player = event.getPlayer();
        if (isExempt(player)) return;
        if (plugin.shouldSuppressFlags(player)) return;

        UUID uuid = player.getUniqueId();
        long now  = System.currentTimeMillis();

        List<Long> times = swapTimes.computeIfAbsent(uuid, k -> new ArrayList<>());
        times.add(now);
        times.removeIf(t -> now - t > WINDOW_MS);

        int swaps = times.size();

        // Sub-check A: raw swap rate
        if (swaps > MAX_SWAPS_PER_SEC) {
            flag(player, "swap_rate=" + swaps + "/s");
            times.clear();
            return;
        }

        // Sub-check B: machine-precise interval variance over 10 swaps
        if (swaps >= 10) {
            List<Long> intervals = new ArrayList<>();
            for (int i = 1; i < times.size(); i++) {
                intervals.add(times.get(i) - times.get(i - 1));
            }
            double mean = intervals.stream().mapToLong(Long::longValue).average().orElse(0);
            double variance = intervals.stream()
                    .mapToDouble(v -> Math.pow(v - mean, 2)).average().orElse(0);
            if (variance < 20) {
                flag(player, String.format("machine_swap swaps=%d var=%.1f mean=%.1fms",
                        swaps, variance, mean));
                times.clear();
            }
        }
    }
}
