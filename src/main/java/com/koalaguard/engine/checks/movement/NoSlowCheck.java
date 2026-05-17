package com.koalaguard.engine.checks.movement;

import com.koalaguard.KoalaGuard;
import com.koalaguard.check.CheckCategory;
import com.koalaguard.engine.check.CheckContext;
import com.koalaguard.engine.check.SimCheck;
import com.koalaguard.engine.state.PositionFrame;

/**
 * NoSlow. While using an item (eating, drinking, blocking, drawing a bow) the
 * vanilla client clamps movement to ~20% speed. Moving near full speed while
 * the use-item state is active is NoSlow. Gated to on-ground, non-ice,
 * stable-transport, no-knockback so momentum can never false-positive.
 */
public final class NoSlowCheck extends SimCheck {

    public NoSlowCheck(KoalaGuard plugin) {
        super(plugin, "noslow", CheckCategory.MOVEMENT, "Full speed while using an item");
    }

    @Override
    public Stage stage() { return Stage.FRAME; }

    @Override
    public void onTick(CheckContext ctx) {
        PositionFrame f = ctx.state.current;
        if (f == null) return;
        if (!ctx.state.usingItem || ctx.unstable()
                || ctx.state.exVehicle || ctx.state.exGliding || ctx.state.exRiptide
                || ctx.state.exLiquid || ctx.state.exWeb) {
            clean(ctx, 1.0);
            return;
        }
        // Need the player genuinely grounded on a normal-friction surface, and
        // no external impulse, so only INPUT speed is being measured.
        if (!f.simGround || f.groundSlipperiness > 0.61) { clean(ctx, 1.0); return; }
        long now = System.currentTimeMillis();
        if (now - ctx.data.lastVelocityMs < 1200 || now - ctx.data.lastDamageMs < 800
                || now - ctx.data.lastTeleportMs < 1500
                || ctx.state.tick - ctx.state.lastSpecialBlockTick < 12) {
            return;
        }

        double max = cfgD("max-use-speed", 0.13);   // vanilla use-speed ≈ 0.054
        double h = f.horizontalSpeed();
        if (h > max) {
            diverge(ctx, (h - max) * cfgD("score-scale", 60.0),
                    cfgD("threshold", 9.0), cfgI("min-streak", 5),
                    String.format("speed %.3f while using item (max %.2f)", h, max), true);
        } else {
            clean(ctx, 1.5);
        }
    }
}
