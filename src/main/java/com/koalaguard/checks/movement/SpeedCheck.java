package com.koalaguard.checks.movement;

import com.koalaguard.KoalaGuard;
import com.koalaguard.checks.Check;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.potion.PotionEffectType;

import java.util.Set;

public class SpeedCheck extends Check {

    // Surfaces that legitimately cause faster movement
    private static final Set<Material> FAST_SURFACES = Set.of(
            Material.ICE, Material.PACKED_ICE, Material.BLUE_ICE,
            Material.SLIME_BLOCK, Material.HONEY_BLOCK
    );

    private static final double BASE_SPRINT_SPEED = 0.45;

    public SpeedCheck(KoalaGuard plugin) { super(plugin, "speed"); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (!isEnabled()) return;
        Player player = event.getPlayer();
        if (isExempt(player)) return;
        if (plugin.shouldSuppressFlags(player)) return;
        if (event.getTo() == null) return;

        // Skip cases that produce legitimate high horizontal speed
        if (player.isFlying() || player.isGliding()) return;
        if (player.isInsideVehicle()) return;
        if (player.getAllowFlight()) return;
        if (player.isRiptiding()) return;

        // Skip if on special surface (ice, slime, honey) — sliding is legitimate
        Block blockBelow = event.getTo().getBlock().getRelative(0, -1, 0);
        if (FAST_SURFACES.contains(blockBelow.getType())) return;

        // Skip if in/on liquid (water currents can push fast)
        if (player.isInWater() || player.isInLava()) return;

        // Skip slime-block bounce grace
        if (plugin.getPlayerState().recentlySlimeBounced(player, 600)) return;

        // Skip riptide/bubble column grace
        if (plugin.getPlayerState().recentlyInBubbleColumn(player, 800)) return;
        if (plugin.getPlayerState().recentlyUsedRiptide(player, 1500)) return;

        // Skip elytra landing grace
        if (plugin.getPlayerState().recentlyLandedFromElytra(player, 800)) return;

        double dx = event.getTo().getX() - event.getFrom().getX();
        double dz = event.getTo().getZ() - event.getFrom().getZ();
        double horizontal = Math.sqrt(dx * dx + dz * dz);

        double maxSpeed = BASE_SPRINT_SPEED;

        // Speed potion
        if (player.hasPotionEffect(PotionEffectType.SPEED)) {
            int amp = player.getPotionEffect(PotionEffectType.SPEED).getAmplifier();
            maxSpeed += 0.12 * (amp + 1);
        }

        // Sprint bonus
        if (player.isSprinting()) maxSpeed += 0.1;

        // Walk-speed modifier (op players or plugin-set walk speed)
        float walkSpeed = player.getWalkSpeed();
        if (walkSpeed > 0.2f) {
            // Default is 0.2; anything higher is set by a plugin/admin
            maxSpeed += (walkSpeed - 0.2f) * 2.5;
        }

        // Conservative multiplier — only flag clearly impossible speed
        if (horizontal > maxSpeed * 1.65) {
            flag(player, String.format("h=%.3f max=%.3f", horizontal, maxSpeed));
        }
    }
}
