package com.koalaguard.checks.movement;

import com.koalaguard.KoalaGuard;
import com.koalaguard.checks.Check;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.PlayerMoveEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Detects Timer / Speed hacks that work by sending extra movement packets.
 *
 * In vanilla, a player sends roughly one movement update per server tick (50ms).
 * Timer cheats increase this to 1.5x–2x, effectively letting the player move faster
 * and attack more often.
 *
 * We detect this by measuring the time between consecutive position updates.
 * If the average inter-packet gap is consistently shorter than one tick (< ~42ms),
 * the player is almost certainly running a timer.
 */
public class TimerCheck extends Check {

    private final Map<UUID, Long> lastMoveMs      = new HashMap<>();
    private final Map<UUID, Long> windowStartMs   = new HashMap<>();
    private final Map<UUID, Integer> fastMoves     = new HashMap<>();

    // Interval shorter than this (ms) is considered "timer fast"
    private static final long FAST_THRESHOLD_MS = 42L;
    // How many fast intervals in one second before flagging
    private static final int  FAST_MOVE_LIMIT   = 12;

    public TimerCheck(KoalaGuard plugin) { super(plugin, "timer"); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (!isEnabled()) return;
        Player player = event.getPlayer();
        if (isExempt(player)) return;
        if (plugin.shouldSuppressFlags(player)) return;
        if (event.getTo() == null) return;

        // Only care about actual position changes (ignore head rotation only)
        if (event.getFrom().getX() == event.getTo().getX()
                && event.getFrom().getY() == event.getTo().getY()
                && event.getFrom().getZ() == event.getTo().getZ()) return;

        UUID uuid = player.getUniqueId();
        long now  = System.currentTimeMillis();
        Long prev = lastMoveMs.put(uuid, now);
        if (prev == null) return;

        long interval = now - prev;

        // Reset 1-second counting window
        long wStart = windowStartMs.getOrDefault(uuid, now);
        if (now - wStart >= 1000) {
            fastMoves.put(uuid, 0);
            windowStartMs.put(uuid, now);
        }

        if (interval < FAST_THRESHOLD_MS) {
            int count = fastMoves.merge(uuid, 1, Integer::sum);
            if (count >= FAST_MOVE_LIMIT) {
                flag(player, "fast_intervals=" + count + " avg<" + FAST_THRESHOLD_MS + "ms");
                fastMoves.put(uuid, 0);
                windowStartMs.put(uuid, now);
            }
        }
    }
}
