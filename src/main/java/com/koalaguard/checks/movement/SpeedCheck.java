package com.koalaguard.checks.movement;

import com.koalaguard.KoalaGuard;
import com.koalaguard.check.MovementCheck;
import com.koalaguard.data.PlayerData;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffectType;

/**
 * Horizontal speed check.
 *
 * Compares the per-tick horizontal displacement against a dynamically
 * computed legitimate maximum (sprint + potions + walk-speed + jump burst).
 * Uses a decaying buffer so a single legitimate sprint-jump spike never
 * flags, but a sustained over-speed (Speed / Bhop / Strafe modules) does.
 */
public final class SpeedCheck extends MovementCheck {

    private static final double BASE_SPRINT = 0.2873; // empirical vanilla sprint bl/tick

    public SpeedCheck(KoalaGuard plugin) {
        super(plugin, "speed", "Moving faster than legitimately possible");
    }

    @Override
    public void handle(PlayerData data, Player player) {
        if (data.exemptFlying || data.exemptVehicle || data.exemptGliding
                || data.exemptLiquid || data.exemptRiptide) { data.subBuffer(k("b"), 1.0); return; }

        long now = System.currentTimeMillis();
        if (now - data.slimeBounceMs < 800 || now - data.bubbleColumnMs < 900
                || now - data.lastRiptideMs < 1600 || now - data.elytraMs < 1000
                || now - data.lastVelocityMs < 900) { data.subBuffer(k("b"), 1.0); return; }

        Material below = player.getLocation().clone().subtract(0, 0.3, 0).getBlock().getType();
        if (below == Material.ICE || below == Material.PACKED_ICE || below == Material.BLUE_ICE
                || below == Material.SLIME_BLOCK || below == Material.HONEY_BLOCK
                || below == Material.SOUL_SAND) { data.subBuffer(k("b"), 1.0); return; }

        double max = BASE_SPRINT;
        if (player.hasPotionEffect(PotionEffectType.SPEED)) {
            int amp = player.getPotionEffect(PotionEffectType.SPEED).getAmplifier();
            max *= 1.0 + 0.2 * (amp + 1);
        }
        // walk-speed customised by other plugins
        float ws = player.getWalkSpeed();
        if (ws > 0.2f) max *= ws / 0.2f;
        // sprint-jump produces a brief burst — allow it generously per-tick
        max += 0.16;
        if (!data.onGround) max += 0.12;            // air momentum carry
        if (data.airTicks <= 2) max += 0.25;        // jump impulse tick

        double speed = data.deltaXZ;

        if (speed > max) {
            double over = speed - max;
            double buf = data.addBuffer(k("b"), 1.0 + over * 6.0, 12.0);
            if (buf >= 5.0) {
                fail(data, player, String.format("h=%.3f max=%.3f over=%.3f", speed, max, over));
                data.setBuffer(k("b"), 1.5);
            }
        } else {
            data.subBuffer(k("b"), 0.5);
        }
    }
}
