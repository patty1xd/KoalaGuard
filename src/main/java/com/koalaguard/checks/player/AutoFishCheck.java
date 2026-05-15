package com.koalaguard.checks.player;

import com.koalaguard.KoalaGuard;
import com.koalaguard.checks.Check;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.PlayerFishEvent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Detects Meteor AutoFish.
 *
 * From Meteor's source (AutoFish.java):
 *   - Listens for the BITE state on the fishing hook.
 *   - Default delay: 0 ticks — immediately reels in on the same tick as the bite.
 *   - Server-side: PlayerFishEvent CAUGHT_FISH fires within < 50 ms of BITE.
 *   - Human minimum reaction time: ~150 ms (measured empirically).
 *
 * Detection:
 *   A) Single sub-100 ms reaction: streak of 2 before flagging.
 *   B) Consistent reaction times: variance < 30 ms² over 5 catches
 *      combined with mean < 80 ms (consistently machine-fast reactions).
 */
public class AutoFishCheck extends Check {

    private final Map<UUID, Long>        lastBiteMs       = new HashMap<>();
    private final Map<UUID, Integer>     fastStreak       = new HashMap<>();
    private final Map<UUID, List<Long>>  reactionTimes    = new HashMap<>();

    private static final long FAST_REACTION_MS = 100L;

    public AutoFishCheck(KoalaGuard plugin) { super(plugin, "autofish"); }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onFish(PlayerFishEvent event) {
        if (!isEnabled()) return;
        Player player = event.getPlayer();
        if (isExempt(player)) return;
        if (plugin.shouldSuppressFlags(player)) return;

        UUID uuid = player.getUniqueId();
        long now  = System.currentTimeMillis();

        if (event.getState() == PlayerFishEvent.State.BITE) {
            lastBiteMs.put(uuid, now);

        } else if (event.getState() == PlayerFishEvent.State.CAUGHT_FISH) {
            Long biteMs = lastBiteMs.remove(uuid);
            if (biteMs == null) return;

            long reaction = now - biteMs;

            // Sub-check A: single fast reaction streak
            if (reaction < FAST_REACTION_MS) {
                int s = fastStreak.merge(uuid, 1, Integer::sum);
                if (s >= 2) {
                    flag(player, "autofish reaction=" + reaction + "ms streak=" + s);
                    fastStreak.put(uuid, 0);
                    reactionTimes.remove(uuid);
                    return;
                }
            } else {
                fastStreak.put(uuid, 0);
            }

            // Sub-check B: consistent reaction variance over 5 catches
            List<Long> times = reactionTimes.computeIfAbsent(uuid, k -> new ArrayList<>());
            times.add(reaction);
            if (times.size() > 10) times.remove(0);

            if (times.size() >= 5) {
                double mean = times.stream().mapToLong(Long::longValue).average().orElse(0);
                double var  = times.stream().mapToDouble(v -> Math.pow(v - mean, 2)).average().orElse(0);
                if (var < 30 && mean < 80) {
                    flag(player, String.format("consistent_fish mean=%.0fms var=%.1f", mean, var));
                    times.clear();
                }
            }
        }
    }
}
