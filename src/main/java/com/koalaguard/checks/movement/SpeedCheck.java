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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Detects Meteor Speed.
 *
 * From Meteor's source (Speed.java):
 *   - "Vanilla" mode: exploits sprint-jump bunny-hop mechanic, boosting
 *     horizontal speed to ~0.7–0.8 blocks/tick per hop.
 *   - "Strafe" mode: applies velocity each tick up to configurable speed (default 1.2).
 *   - "NCP" mode: mimics NCP-safe speed packets.
 *   - Observable: sustained horizontal speed above vanilla sprint maximum.
 *
 * Vanilla max horizontal speeds (blocks per move event ≈ blocks/tick):
 *   Walking sprint:     ~0.286 bl/tick
 *   Sprint-jump peak:   ~0.42  bl/tick (legitimate, lasts 1-2 ticks)
 *   Speed I sprint:     ~0.37  bl/tick
 *   Speed II sprint:    ~0.45  bl/tick
 *
 * Detection:
 *   Measure per-move horizontal displacement.
 *   Allow a brief spike (sprint-jump burst) but flag if the rolling mean
 *   of the last 5 moves exceeds the player's computed maximum.
 *   Multiplier 1.35 gives headroom for minor server-side desync.
 */
public class SpeedCheck extends Check {

    private static final Set<Material> FAST_SURFACES = Set.of(
            Material.ICE, Material.PACKED_ICE, Material.BLUE_ICE,
            Material.SLIME_BLOCK, Material.HONEY_BLOCK
    );

    private static final double BASE_SPRINT = 0.286; // vanilla sprint (not jumping)

    private final Map<UUID, List<Double>> recentSpeeds = new HashMap<>();

    public SpeedCheck(KoalaGuard plugin) { super(plugin, "speed"); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (!isEnabled()) return;
        Player player = event.getPlayer();
        if (isExempt(player)) return;
        if (plugin.shouldSuppressFlags(player)) return;
        if (event.getTo() == null) return;

        if (player.isFlying() || player.isGliding()) return;
        if (player.isInsideVehicle()) return;
        if (player.getAllowFlight()) return;
        if (player.isRiptiding()) return;
        if (player.isInWater() || player.isInLava()) return;

        Block below = event.getTo().getBlock().getRelative(0, -1, 0);
        if (FAST_SURFACES.contains(below.getType())) return;

        if (plugin.getPlayerState().recentlySlimeBounced(player, 600)) return;
        if (plugin.getPlayerState().recentlyInBubbleColumn(player, 800)) return;
        if (plugin.getPlayerState().recentlyUsedRiptide(player, 1500)) return;
        if (plugin.getPlayerState().recentlyLandedFromElytra(player, 800)) return;

        double dx = event.getTo().getX() - event.getFrom().getX();
        double dz = event.getTo().getZ() - event.getFrom().getZ();
        double horizontal = Math.sqrt(dx * dx + dz * dz);

        double maxSpeed = BASE_SPRINT;

        // Speed potion: each level adds 0.1 to multiplier
        // Speed I  → ×1.2 sprint  → ~0.343 bl/tick
        // Speed II → ×1.4 sprint  → ~0.400 bl/tick
        if (player.hasPotionEffect(PotionEffectType.SPEED)) {
            int amp = player.getPotionEffect(PotionEffectType.SPEED).getAmplifier();
            maxSpeed *= 1.0 + 0.2 * (amp + 1);
        }

        // Sprint jump gives a ~1.5× burst for 1-2 ticks — accounted for in multiplier
        if (player.isSprinting()) maxSpeed += 0.04;

        // Custom walk-speed (set by plugins/admins — default is 0.2)
        float walkSpeed = player.getWalkSpeed();
        if (walkSpeed > 0.2f) {
            maxSpeed += (walkSpeed - 0.2f) * 2.5;
        }

        // Rolling average over 5 move events to absorb jump bursts
        UUID uuid = player.getUniqueId();
        List<Double> speeds = recentSpeeds.computeIfAbsent(uuid, k -> new ArrayList<>());
        speeds.add(horizontal);
        if (speeds.size() > 5) speeds.remove(0);

        if (speeds.size() == 5) {
            double mean = speeds.stream().mapToDouble(Double::doubleValue).average().orElse(0);
            if (mean > maxSpeed * 1.35) {
                flag(player, String.format("avg_h=%.3f max=%.3f", mean, maxSpeed));
                speeds.clear();
            }
        }
    }
}
