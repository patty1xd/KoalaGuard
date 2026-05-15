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

        // Hard exemptions
        if (player.getAllowFlight() || player.isFlying()) return;
        if (player.getGameMode() == GameMode.CREATIVE || player.getGameMode() == GameMode.SPECTATOR) return;
        if (player.isGliding() || player.isInsideVehicle()) return;
        if (player.isRiptiding()) return;

        // Potion exemptions
        if (player.hasPotionEffect(PotionEffectType.LEVITATION)) return;
        if (player.hasPotionEffect(PotionEffectType.JUMP_BOOST)) return;
        if (player.hasPotionEffect(PotionEffectType.SLOW_FALLING)) return;

        // State exemptions
        if (plugin.getPlayerState().recentlySlimeBounced(player, 800)) return;
        if (plugin.getPlayerState().recentlyUsedRiptide(player, 1500)) return;
        if (plugin.getPlayerState().recentlyInBubbleColumn(player, 1000)) return;
        if (plugin.getPlayerState().recentlyLandedFromElytra(player, 1000)) return;

        // Check if player is in/on water or lava (swimming/floating is not fly)
        if (player.isInWater() || player.isInLava()) {
            airTicks.remove(player.getUniqueId());
            return;
        }

        // Check if block below is a non-solid that still "holds" the player (like
        // fences, stairs, snow, etc.)
        boolean onGround = isOnGround(player, event);

        UUID uuid = player.getUniqueId();
        if (!onGround) {
            int ticks = airTicks.merge(uuid, 1, Integer::sum);
            double dy  = event.getTo().getY() - event.getFrom().getY();

            // Only flag sustained upward movement (not just a normal jump arc)
            // 40 ticks = 2 seconds of continuous upward drift
            if (ticks > 40 && dy >= 0.02) {
                flag(player, "airTicks=" + ticks + " dy=" + String.format("%.3f", dy));
            }
        } else {
            airTicks.remove(uuid);
        }
    }

    private boolean isOnGround(Player player, PlayerMoveEvent event) {
        if (player.isOnGround()) return true;

        // Server-side ground check for common "holding" blocks
        org.bukkit.Location loc = event.getTo();
        if (loc == null) return false;

        Material below = loc.getBlock().getRelative(0, -1, 0).getType();
        if (below.isSolid()) return true;

        // Slabs, stairs, etc. (non-solid but walkable)
        Material atFeet = loc.getBlock().getType();
        return atFeet.isSolid();
    }
}
