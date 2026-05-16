package com.koalaguard.checks.movement;

import com.koalaguard.KoalaGuard;
import com.koalaguard.check.MovementCheck;
import com.koalaguard.data.PlayerData;
import org.bukkit.Material;
import org.bukkit.entity.Player;

/**
 * Climb-speed detection.
 *
 * Vanilla climbing tops out at ≈0.118 bl/tick upward on ladders/vines.
 * Spider / FastClimb modules scale this up dramatically.
 */
public final class FastClimbCheck extends MovementCheck {

    private static final double MAX_CLIMB_UP = 0.20; // generous vs 0.118 vanilla

    public FastClimbCheck(KoalaGuard plugin) {
        super(plugin, "fastclimb", "Climbing faster than vanilla allows");
    }

    @Override
    public void handle(PlayerData data, Player player) {
        if (data.exemptFlying || data.exemptVehicle || data.exemptRiptide
                || data.exemptLevitation) { data.subBuffer(k("b"), 1.0); return; }
        long now = System.currentTimeMillis();
        if (now - data.lastVelocityMs < 900 || now - data.lastDamageMs < 700) {
            data.subBuffer(k("b"), 1.0);
            return;
        }

        Material at = player.getLocation().getBlock().getType();
        boolean climbable = at == Material.LADDER || at == Material.VINE
                || at == Material.SCAFFOLDING || at == Material.TWISTING_VINES
                || at == Material.TWISTING_VINES_PLANT || at == Material.WEEPING_VINES
                || at == Material.WEEPING_VINES_PLANT || at == Material.CAVE_VINES
                || at == Material.CAVE_VINES_PLANT;
        if (!climbable) { data.subBuffer(k("b"), 1.0); return; }

        if (data.deltaY > MAX_CLIMB_UP) {
            double buf = data.addBuffer(k("b"), 1.5, 8.0);
            if (buf >= 4.0) {
                fail(data, player, String.format("climbY=%.3f max=%.3f", data.deltaY, MAX_CLIMB_UP));
                data.setBuffer(k("b"), 1.0);
            }
        } else {
            data.subBuffer(k("b"), 1.0);
        }
    }
}
