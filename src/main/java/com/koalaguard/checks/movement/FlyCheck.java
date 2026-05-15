package com.koalaguard.checks.movement;

import com.koalaguard.KoalaGuard;
import com.koalaguard.checks.Check;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.potion.PotionEffectType;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Detects Meteor Flight.
 *
 * From Meteor's source (Flight.java):
 *   - "Vanilla" mode: repeatedly cancels fall velocity, keeping player airborne.
 *   - "Creative" mode: sets fly flag server-side (detected by allowFlight, skipped here).
 *   - Server-observable: player remains at constant or increasing Y for > 2 seconds
 *     with no legitimate explanation.
 *
 * Detection:
 *   Count consecutive move events where the player is NOT on the ground.
 *   Require sustained upward or level flight (dy >= -0.08 per tick) for
 *   > 30 ticks (1.5 s) before flagging.
 *   -0.08 is the terminal velocity of a player in free-fall after 1 tick,
 *   so values above this mean the player is NOT naturally falling.
 */
public class FlyCheck extends Check {

    private final Map<UUID, Integer> airTicks = new HashMap<>();

    public FlyCheck(KoalaGuard plugin) { super(plugin, "fly"); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (!isEnabled()) return;
        Player player = event.getPlayer();
        if (isExempt(player)) return;
        if (plugin.shouldSuppressFlags(player)) return;
        if (event.getTo() == null) return;

        if (player.getAllowFlight() || player.isFlying()) return;
        if (player.getGameMode() == GameMode.CREATIVE || player.getGameMode() == GameMode.SPECTATOR) return;
        if (player.isGliding() || player.isInsideVehicle() || player.isRiptiding()) return;
        if (player.isInWater() || player.isInLava()) { airTicks.remove(player.getUniqueId()); return; }

        if (player.hasPotionEffect(PotionEffectType.LEVITATION)) return;
        if (player.hasPotionEffect(PotionEffectType.SLOW_FALLING)) return;

        if (plugin.getPlayerState().recentlySlimeBounced(player, 1200)) return;
        if (plugin.getPlayerState().recentlyUsedRiptide(player, 2000)) return;
        if (plugin.getPlayerState().recentlyInBubbleColumn(player, 1200)) return;
        if (plugin.getPlayerState().recentlyLandedFromElytra(player, 1500)) return;

        boolean onGround = isOnGround(player, event);
        UUID uuid = player.getUniqueId();

        if (!onGround) {
            double dy = event.getTo().getY() - event.getFrom().getY();

            // Natural gravity: dy decreases by ~0.08 each tick
            // If dy > -0.08 the player is resisting gravity → possible fly
            // (This also catches players hovering at constant Y)
            if (dy >= -0.09) {
                int ticks = airTicks.merge(uuid, 1, Integer::sum);
                // Allow Jump Boost: it adds ~0.1 per amp level to jump height
                if (player.hasPotionEffect(PotionEffectType.JUMP_BOOST)) {
                    return; // generous exemption — jump boost extends airtime
                }
                if (ticks > 30 && dy >= -0.04) {
                    flag(player, "air_ticks=" + ticks + " dy=" + String.format("%.3f", dy));
                    airTicks.put(uuid, 20); // reset to mid-value, don't spam flags
                }
            } else {
                // Falling at natural speed — not fly
                airTicks.merge(uuid, 1, Integer::sum); // still in air, but naturally
            }
        } else {
            airTicks.remove(uuid);
        }
    }

    private boolean isOnGround(Player player, PlayerMoveEvent event) {
        if (player.isOnGround()) return true;
        org.bukkit.Location to = event.getTo();
        if (to == null) return false;
        // Server-side solid block below
        Material below = to.getBlock().getRelative(0, -1, 0).getType();
        if (below.isSolid()) return true;
        // At-feet block (slabs, stairs, etc.)
        return to.getBlock().getType().isSolid();
    }
}
