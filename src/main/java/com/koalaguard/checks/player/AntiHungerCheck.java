package com.koalaguard.checks.player;

import com.koalaguard.KoalaGuard;
import com.koalaguard.checks.Check;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.potion.PotionEffectType;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Detects Meteor's AntiHunger module.
 *
 * What Meteor AntiHunger actually does (verified from source):
 *   1. Cancels ServerboundPlayerCommandPacket START_SPRINTING packets.
 *      → server.isSprinting() becomes false even while player moves fast.
 *   2. Spoofs onGround=false in movement packets while actually on ground.
 *
 * Observable server-side via Bukkit:
 *   player.isSprinting() == false while horizontal speed >= sprint threshold.
 *   Paper reflects the client-sent sprint flag, so the desync is measurable.
 *
 * We require 20+ consecutive moves of this pattern (~1 second) before flagging
 * to avoid one-off false positives from knockback or ice momentum.
 */
public class AntiHungerCheck extends Check {

    // Vanilla sprint ≈ 0.286 bl/tick. Conservative threshold above walk (0.22).
    private static final double SPRINT_THRESHOLD = 0.26;

    private final Map<UUID, Integer> streak = new HashMap<>();

    public AntiHungerCheck(KoalaGuard plugin) { super(plugin, "antihunger"); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (!isEnabled()) return;
        Player player = event.getPlayer();
        if (isExempt(player)) return;
        if (plugin.shouldSuppressFlags(player)) return;
        if (event.getTo() == null) return;
        if (player.isFlying() || player.isGliding() || player.isInsideVehicle()) return;
        if (player.isInWater() || player.isInLava()) return;

        // Speed potion legitimately increases movement without sprinting flag on some servers
        if (player.hasPotionEffect(PotionEffectType.SPEED)) return;

        // Only flag grounded movement (the onGround spoof also applies only on ground)
        if (!player.isOnGround() && !plugin.getPlayerState().recentlyTookDamage(player, 200)) {
            streak.put(player.getUniqueId(), 0);
            return;
        }

        double dx = event.getTo().getX() - event.getFrom().getX();
        double dz = event.getTo().getZ() - event.getFrom().getZ();
        double h = Math.sqrt(dx * dx + dz * dz);

        UUID uuid = player.getUniqueId();

        if (h >= SPRINT_THRESHOLD && !player.isSprinting()) {
            int s = streak.merge(uuid, 1, Integer::sum);
            if (s >= 20) {
                flag(player, String.format("sprint_desync speed=%.3f streak=%d", h, s));
                streak.put(uuid, 0);
            }
        } else {
            streak.put(uuid, 0);
        }
    }
}
