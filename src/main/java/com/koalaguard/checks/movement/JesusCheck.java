package com.koalaguard.checks.movement;

import com.koalaguard.KoalaGuard;
import com.koalaguard.checks.Check;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.PlayerMoveEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Detects Meteor Jesus (water walking).
 *
 * From Meteor's source (Jesus.java):
 *   - "Packet" mode: sends fake onGround=true packets while over water,
 *     making the server treat the player as standing on the surface.
 *   - "Vanilla" / "Timer" modes: exploit stepping/timer to walk on water.
 *   - Server-observable: player.isOnGround() returns true while the block
 *     at the player's feet position is a liquid (WATER / LAVA), and the
 *     player is moving horizontally across the surface without sinking.
 *
 * Detection:
 *   player.isOnGround() AND feet block is a liquid AND horizontal movement
 *   is occurring (player is not just floating still in a pool).
 *   Require streak of 5 consecutive such ticks before flagging to avoid
 *   FPs from normal water-entry moments.
 */
public class JesusCheck extends Check {

    private final Map<UUID, Integer> liquidGroundStreak = new HashMap<>();

    public JesusCheck(KoalaGuard plugin) { super(plugin, "jesus"); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (!isEnabled()) return;
        Player player = event.getPlayer();
        if (isExempt(player)) return;
        if (plugin.shouldSuppressFlags(player)) return;
        if (event.getTo() == null) return;

        // Legitimate exemptions
        if (player.isFlying() || player.getAllowFlight()) return;
        if (player.isInsideVehicle()) return;
        if (player.hasPotionEffect(org.bukkit.potion.PotionEffectType.SLOW_FALLING)) return;
        if (plugin.getPlayerState().recentlyUsedRiptide(player, 2000)) return;

        Material feet = event.getTo().getBlock().getType();
        boolean inLiquid = feet == Material.WATER || feet == Material.LAVA;

        UUID uuid = player.getUniqueId();

        if (inLiquid && player.isOnGround()) {
            // Check horizontal movement (not just standing still in shallow water)
            double dx = event.getTo().getX() - event.getFrom().getX();
            double dz = event.getTo().getZ() - event.getFrom().getZ();
            double h = Math.sqrt(dx * dx + dz * dz);

            if (h > 0.05) {
                int streak = liquidGroundStreak.merge(uuid, 1, Integer::sum);
                if (streak >= 5) {
                    flag(player, "walking_on=" + feet.name() + " h=" + String.format("%.3f", h));
                    liquidGroundStreak.put(uuid, 0);
                }
                return;
            }
        }
        liquidGroundStreak.put(uuid, 0);
    }
}
