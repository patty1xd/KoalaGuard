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

/**
 * Detects Meteor Velocity / AntiKnockback.
 *
 * From Meteor's source (Velocity.java):
 *   - Multiplies the incoming knockback velocity by configurable factors.
 *   - Default: horizontal=0%, vertical=0% → complete knockback cancellation.
 *   - "Vanilla": h=100%, v=100% → no change (used for bypass).
 *   - Server-side: PlayerVelocityEvent fires with the velocity the server
 *     wants to apply. AntiKnockback makes the client ignore it, but the
 *     server still fires the event with the full velocity.
 *
 * We detect knockback absorption by comparing EntityKnockbackEvent's
 * final knockback vector magnitude against the player's actual velocity
 * set via PlayerVelocityEvent. The ratio approaching 0 = cancellation.
 *
 * Require 3 consecutive near-zero-ratio events before flagging.
 * Threshold 0.20 absorbs natural ice/water knockback reduction.
 */
public class VelocityCheck extends Check {

    private final Map<UUID, Vector>  expectedKb     = new HashMap<>();
    private final Map<UUID, Integer> lowRatioStreak = new HashMap<>();

    public VelocityCheck(KoalaGuard plugin) { super(plugin, "velocity"); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onKnockback(EntityKnockbackEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        if (isExempt(player)) return;
        Vector kb = event.getFinalKnockback();
        if (kb.length() > 0.1) {
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
        if (expected.length() < 0.1) return;

        double ratio = event.getVelocity().length() / expected.length();

        // Meteor default (0% h, 0% v): ratio ≈ 0.0–0.05
        // Meteor partial (50% h):      ratio ≈ 0.3–0.5
        // Legitimate reductions:        ratio typically 0.6–1.0
        if (ratio < 0.20) {
            int streak = lowRatioStreak.merge(uuid, 1, Integer::sum);
            if (streak >= 3) {
                flag(player, String.format("kb_ratio=%.2f expected=%.2f streak=%d",
                        ratio, expected.length(), streak));
                lowRatioStreak.put(uuid, 0);
            }
        } else {
            lowRatioStreak.put(uuid, 0);
        }
    }
}
