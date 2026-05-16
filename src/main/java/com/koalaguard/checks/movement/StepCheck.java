package com.koalaguard.checks.movement;

import com.koalaguard.KoalaGuard;
import com.koalaguard.check.MovementCheck;
import com.koalaguard.data.PlayerData;
import com.koalaguard.util.MovementPredictor;
import org.bukkit.entity.Player;

/**
 * Step — vanilla auto-steps ≤0.6 per tick. Step modules teleport up full
 * blocks (1.0+). A jump's first tick (~0.42) is preceded by upward velocity;
 * a step is a single >0.6 Y gain from a grounded tick with ~0 prior dy. Flag
 * + lagback the illegitimate elevation gain.
 */
public final class StepCheck extends MovementCheck {

    private static final double VANILLA_STEP = 0.6;

    public StepCheck(KoalaGuard plugin) {
        super(plugin, "step", "Stepping up more than vanilla (0.6) without jumping");
    }

    @Override
    public void handle(PlayerData d, Player player) {
        if (MovementPredictor.verticalUnsafe(d, player)) { d.subBuffer(k("b"), 1.0); return; }

        double dy = d.deltaY;
        if (dy <= VANILLA_STEP + 0.06) { d.subBuffer(k("b"), 0.5); return; }

        boolean wasJumpArc = d.lastDeltaY >= 0.30;          // jumps have prior upward velocity
        boolean fromGround = d.lastOnGround || d.groundTicks <= 1;

        if (!wasJumpArc && fromGround && (d.onGround || d.nearGround)) {
            double buf = d.addBuffer(k("b"), 2.5, 8.0);
            if (buf >= 4.0) {
                failAndSetback(d, player, String.format("stepY=%.3f vanillaMax=%.1f", dy, VANILLA_STEP));
                d.setBuffer(k("b"), 1.0);
            }
        } else {
            d.subBuffer(k("b"), 1.0);
        }
    }
}
