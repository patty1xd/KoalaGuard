package com.koalaguard.checks.player;

import com.koalaguard.KoalaGuard;
import com.koalaguard.checks.Check;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.PlayerItemConsumeEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Detects Meteor AutoEat.
 *
 * From Meteor's source (AutoEat.java):
 *   - Starts eating when hunger falls below a configurable threshold (default 14).
 *   - "FastEat" mode (Meteor): sends a fake START_USE_ITEM and immediately
 *     sends RELEASE_USE_ITEM, consuming food in 0 ticks instead of 32.
 *   - Even in normal mode, AutoEat may re-eat within the same tick as the
 *     previous consume event if hunger drops again immediately.
 *
 * Vanilla eating time: 32 ticks = 1600 ms for all food items.
 * FastEat: consumes in < 100 ms (essentially 0 ticks).
 *
 * Detection:
 *   A) Food consumed in < 800 ms of the previous consume event (50% of vanilla
 *      minimum), indicating FastEat or bot-level eating speed. Streak >= 2.
 *   B) Eating while at max hunger (20/20 food points) repeatedly:
 *      humans don't eat at full hunger; AutoEat does this by default.
 *      Streak >= 3 full-hunger eats in < 10 seconds.
 */
public class AutoEatCheck extends Check {

    private final Map<UUID, Long>    lastEatMs        = new HashMap<>();
    private final Map<UUID, Integer> fastEatStreak    = new HashMap<>();
    private final Map<UUID, Integer> fullHungerStreak = new HashMap<>();
    private final Map<UUID, Long>    fullHungerWindow = new HashMap<>();

    private static final long   FAST_EAT_THRESHOLD_MS = 800L;
    private static final long   FULL_HUNGER_WINDOW_MS = 10_000L;

    public AutoEatCheck(KoalaGuard plugin) { super(plugin, "autoeat"); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onConsume(PlayerItemConsumeEvent event) {
        if (!isEnabled()) return;
        Player player = event.getPlayer();
        if (isExempt(player)) return;
        if (plugin.shouldSuppressFlags(player)) return;
        if (!event.getItem().getType().isEdible()) return;

        UUID uuid = player.getUniqueId();
        long now  = System.currentTimeMillis();

        // Sub-check A: eating too fast
        Long lastMs = lastEatMs.get(uuid);
        if (lastMs != null) {
            long elapsed = now - lastMs;
            if (elapsed < FAST_EAT_THRESHOLD_MS) {
                int s = fastEatStreak.merge(uuid, 1, Integer::sum);
                if (s >= 2) {
                    flag(player, "fast_eat elapsed=" + elapsed + "ms streak=" + s);
                    fastEatStreak.put(uuid, 0);
                }
            } else {
                fastEatStreak.put(uuid, 0);
            }
        }
        lastEatMs.put(uuid, now);

        // Sub-check B: eating at full hunger
        int food = player.getFoodLevel();
        if (food >= 20) {
            long wStart = fullHungerWindow.getOrDefault(uuid, now);
            if (now - wStart > FULL_HUNGER_WINDOW_MS) {
                fullHungerStreak.put(uuid, 1);
                fullHungerWindow.put(uuid, now);
            } else {
                int s = fullHungerStreak.merge(uuid, 1, Integer::sum);
                if (s >= 3) {
                    flag(player, "eat_at_full_hunger streak=" + s);
                    fullHungerStreak.put(uuid, 0);
                    fullHungerWindow.put(uuid, now);
                }
            }
        } else {
            fullHungerStreak.put(uuid, 0);
        }
    }
}
