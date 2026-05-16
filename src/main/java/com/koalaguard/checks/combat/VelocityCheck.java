package com.koalaguard.checks.combat;

import com.koalaguard.KoalaGuard;
import com.koalaguard.check.CheckCategory;
import com.koalaguard.check.ListenerCheck;
import com.koalaguard.data.PlayerData;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.PlayerVelocityEvent;
import org.bukkit.util.Vector;

/**
 * Velocity / AntiKnockback detection.
 *
 * When the server applies combat knockback it fires PlayerVelocityEvent.
 * A few ticks later we compare how far the player actually travelled
 * horizontally against what was applied. AntiKB clients absorb the velocity,
 * producing a near-zero ratio. A streak is required so walls / corners never
 * cause a false positive.
 */
public final class VelocityCheck extends ListenerCheck {

    public VelocityCheck(KoalaGuard plugin) {
        super(plugin, "velocity", CheckCategory.COMBAT, "Reducing or cancelling knockback");
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onVelocity(PlayerVelocityEvent event) {
        if (!isEnabled()) return;
        Player player = event.getPlayer();
        if (isExempt(player)) return;
        PlayerData data = plugin.getDataManager().get(player);
        if (data == null) return;

        // Only judge combat knockback (velocity right after taking damage).
        if (System.currentTimeMillis() - data.lastDamageMs > 250) return;

        Vector v = event.getVelocity();
        double expected = Math.hypot(v.getX(), v.getZ());
        if (expected < 0.20) return;

        final Location start = player.getLocation().clone();
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (!player.isOnline() || !data.isAlive()) return;
            if (plugin.getSafetyManager().shouldSuppress(data, player)) return;

            Location now = player.getLocation();
            double moved = Math.hypot(now.getX() - start.getX(), now.getZ() - start.getZ());
            double ratio = moved / Math.max(0.001, expected * 1.6); // 2 ticks of travel ≈ 1.6×

            if (ratio < 0.30) {
                int s = data.incInt(k("s"));
                if (s >= 3) {
                    fail(data, player, String.format("kb absorbed ratio=%.2f exp=%.2f streak=%d",
                            ratio, expected, s));
                    data.setInt(k("s"), 0);
                }
            } else {
                data.setInt(k("s"), 0);
            }
        }, 2L);
    }
}
