package com.koalaguard.checks.movement;

import com.koalaguard.KoalaGuard;
import com.koalaguard.checks.Check;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.potion.PotionEffectType;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Detects Meteor NoFall.
 *
 * From Meteor's source (NoFall.java):
 *   - "Packet" mode (default): just before landing, sends a fake move packet
 *     with onGround=true, resetting the server's tracked fall distance to 0.
 *     The server then calculates 0 fall damage and none is applied.
 *   - "NoGround" mode: keeps onGround=false so the server never applies damage.
 *   - "Legit" mode: mimics NCP-safe behavior.
 *
 * Server-side observable:
 *   The server tracks accumulated fall distance via player.getFallDistance().
 *   In Packet mode, the fake onGround=true resets this mid-air.
 *   We detect this: player has been airborne (Y decreasing significantly),
 *   yet their fall distance resets to near-zero WITHOUT landing on a block.
 *
 * Detection:
 *   Track max fall distance seen since last landing.
 *   On each move event: if the player's Y dropped significantly since last
 *   tick (>= 3.0 blocks total accumulated) AND fall distance suddenly resets
 *   to < 0.5 but the player is NOT on solid ground → NoFall packet sent.
 *   Require streak of 2.
 */
public class NoFallCheck extends Check {

    private final Map<UUID, Double> maxFallDist  = new HashMap<>();
    private final Map<UUID, Double> prevY        = new HashMap<>();
    private final Map<UUID, Integer> resetStreak = new HashMap<>();

    private static final double MIN_FALL_FOR_DAMAGE = 3.0; // vanilla: > 3 blocks = damage

    public NoFallCheck(KoalaGuard plugin) { super(plugin, "nofall"); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (!isEnabled()) return;
        Player player = event.getPlayer();
        if (isExempt(player)) return;
        if (plugin.shouldSuppressFlags(player)) return;
        if (event.getTo() == null) return;

        if (player.getAllowFlight() || player.isFlying()) return;
        if (player.getGameMode() == GameMode.CREATIVE || player.getGameMode() == GameMode.SPECTATOR) return;
        if (player.isGliding() || player.isInsideVehicle()) return;
        if (player.isInWater() || player.isInLava()) { resetTracking(player.getUniqueId()); return; }
        if (player.hasPotionEffect(PotionEffectType.SLOW_FALLING)) return;
        if (plugin.getPlayerState().recentlyUsedRiptide(player, 2000)) return;
        if (plugin.getPlayerState().recentlySlimeBounced(player, 1000)) return;
        if (plugin.getPlayerState().recentlyInBubbleColumn(player, 1200)) return;

        UUID   uuid    = player.getUniqueId();
        double currY   = event.getTo().getY();
        double pY      = prevY.getOrDefault(uuid, currY);
        double fallDst = player.getFallDistance();

        prevY.put(uuid, currY);

        // Track the maximum fall distance during the current fall
        double tracked = maxFallDist.getOrDefault(uuid, 0.0);
        if (fallDst > tracked) {
            maxFallDist.put(uuid, fallDst);
            tracked = fallDst;
        }

        // Reset when player lands naturally (on ground, fall distance = 0)
        if (player.isOnGround() || fallDst == 0) {
            if (tracked < MIN_FALL_FOR_DAMAGE) {
                // Short fall — not suspicious
                resetTracking(uuid);
                return;
            }
            // They had a big fall but fall distance reset AND they're on solid ground
            // → normal landing, not NoFall
            resetTracking(uuid);
            return;
        }

        // Mid-air fall distance reset WITHOUT landing on solid ground
        // This is the NoFall "Packet" signature
        if (tracked >= MIN_FALL_FOR_DAMAGE && fallDst < 0.5 && currY < pY - 0.01) {
            // Player is still falling (Y decreasing) but fall distance reset
            boolean onSolid = event.getTo().getBlock().getRelative(0, -1, 0).getType().isSolid()
                    || event.getTo().getBlock().getType().isSolid();
            if (!onSolid) {
                int s = resetStreak.merge(uuid, 1, Integer::sum);
                if (s >= 2) {
                    flag(player, String.format("fall_reset tracked=%.1f blocks streak=%d",
                            tracked, s));
                    resetTracking(uuid);
                }
                return;
            }
        }

        // Fall distance dropped back to 0 without being on ground — reset tracking window
        if (fallDst == 0 && !player.isOnGround()) {
            maxFallDist.put(uuid, 0.0);
        }
    }

    private void resetTracking(UUID uuid) {
        maxFallDist.remove(uuid);
        resetStreak.put(uuid, 0);
    }
}
