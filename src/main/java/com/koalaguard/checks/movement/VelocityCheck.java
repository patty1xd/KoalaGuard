package com.koalaguard.checks.movement;

import com.koalaguard.KoalaGuard;
import com.koalaguard.checks.Check;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.entity.EntityKnockbackEvent;
import org.bukkit.event.player.PlayerVelocityEvent;
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class VelocityCheck extends Check {

    // Track the most recent knockback applied to each player
    private final Map<UUID, Vector> expectedKb = new HashMap<>();
    // Streak counter: consecutive low-ratio events
    private final Map<UUID, Integer> lowRatioStreak = new HashMap<>();

    public VelocityCheck(KoalaGuard plugin) { super(plugin, "velocity"); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onKnockback(EntityKnockbackEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        if (isExempt(player)) return;
        Vector kb = event.getFinalKnockback();
        if (kb.length() > 0.05) {
            expectedKb.put(player.getUniqueId(), kb.clone());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onVelocity(PlayerVelocityEvent event) {
        if (!isEnabled()) return;
        Player player = event.getPlayer();
        if (isExempt(player)) return;
        if (plugin.shouldSuppressFlags(player)) return;

        UUID uuid = player.getUniqueId();
        Vector expected = expectedKb.remove(uuid);
        if (expected == null) return;

        // Ignore trivially small knockbacks (ice/water edge cases)
        if (expected.length() < 0.1) return;

        Vector actual = event.getVelocity();
        double ratio  = actual.length() / expected.length();

        // Conservative: only flag near-total cancellation
        // Players on ice or using specific mechanics can legitimately reduce kb
        if (ratio < 0.15) {
            int streak = lowRatioStreak.merge(uuid, 1, Integer::sum);
            // Require 3 consecutive low-ratio events to reduce false positives
            if (streak >= 3) {
                flag(player, "ratio=" + String.format("%.2f", ratio) + " streak=" + streak);
                lowRatioStreak.put(uuid, 0);
            }
        } else {
            lowRatioStreak.put(uuid, 0);
        }
    }
}
