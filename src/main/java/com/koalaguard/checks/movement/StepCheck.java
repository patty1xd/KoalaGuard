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
 * Detects Meteor Step.
 *
 * From Meteor's source (Step.java):
 *   - Sets the player's step height above the vanilla 0.6 blocks.
 *   - Default height: 1.0 block — allows stepping up full blocks without jumping.
 *   - "NCP" mode: gradually increases step height over several ticks to bypass NCP.
 *   - Server-side: PlayerMoveEvent shows a Y delta > 0.6 on a single tick
 *     while the player is on the ground (no jump needed).
 *
 * Vanilla step height: 0.6 blocks per tick (allows stepping up slabs/stairs).
 * Any single-tick Y increase > 0.6 while the player appears to be on ground
 * (no falling velocity preceding it) = Step cheat.
 *
 * Detection:
 *   Y delta > 0.65 (with 0.05 buffer) in a single move event AND
 *   the previous tick had the player either on ground or at very low Y delta
 *   (ruling out a legitimate jump arc where Y starts at +0.42 then decreases).
 *
 * Legitimate jump guard:
 *   First tick of a vanilla jump: dy ≈ +0.42. Second tick: +0.33. Etc.
 *   We track the previous dy — if it was also large and positive, it's a
 *   jump arc, not step. Step produces a ONE-TIME spike from ground level.
 *
 * Require streak of 2 to absorb lag spikes.
 */
public class StepCheck extends Check {

    private final Map<UUID, Double>  prevDy      = new HashMap<>();
    private final Map<UUID, Integer> stepStreak  = new HashMap<>();

    private static final double VANILLA_STEP  = 0.6;
    private static final double JUMP_FIRST_DY = 0.38; // first tick of a jump ≈ 0.42, with buffer

    public StepCheck(KoalaGuard plugin) { super(plugin, "step"); }

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
        if (player.isInWater() || player.isInLava()) { prevDy.remove(player.getUniqueId()); return; }
        if (player.hasPotionEffect(PotionEffectType.JUMP_BOOST)) return;
        if (plugin.getPlayerState().recentlySlimeBounced(player, 1200)) return;
        if (plugin.getPlayerState().recentlyInBubbleColumn(player, 1200)) return;
        if (plugin.getPlayerState().recentlyUsedRiptide(player, 2000)) return;

        UUID   uuid  = player.getUniqueId();
        double dy    = event.getTo().getY() - event.getFrom().getY();
        double pDy   = prevDy.getOrDefault(uuid, 0.0);
        prevDy.put(uuid, dy);

        if (dy <= VANILLA_STEP) {
            stepStreak.put(uuid, 0);
            return;
        }

        // Large positive dy — could be step or start of jump
        // Jump: first tick dy ≈ +0.42, preceded by dy ≈ 0 (on ground)
        //       a jump produces ONLY ONE large-dy tick then decreases
        // Step: dy ≈ 1.0 from ground level, previous dy was near 0

        // If the previous dy was also large & positive → this is part of a jump arc
        if (pDy >= JUMP_FIRST_DY) {
            stepStreak.put(uuid, 0);
            return;
        }

        // dy > 0.6 from near-ground (prev dy ≈ 0) AND block above feet is solid
        // (actual step requires a solid block to step onto)
        Material above = event.getFrom().getBlock().getRelative(0, 1, 0).getType();
        if (!above.isSolid()) {
            stepStreak.put(uuid, 0);
            return;
        }

        int s = stepStreak.merge(uuid, 1, Integer::sum);
        if (s >= 2) {
            flag(player, String.format("step dy=%.3f vanilla_max=%.1f streak=%d", dy, VANILLA_STEP, s));
            stepStreak.put(uuid, 0);
        }
    }
}
