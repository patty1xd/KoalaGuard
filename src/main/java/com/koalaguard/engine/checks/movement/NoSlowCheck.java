package com.koalaguard.engine.checks.movement;

import com.koalaguard.KoalaGuard;
import com.koalaguard.check.CheckCategory;
import com.koalaguard.engine.check.CheckContext;
import com.koalaguard.engine.check.SimCheck;
import com.koalaguard.engine.state.PositionFrame;

/**
 * NoSlow. While genuinely using an item (eating, drinking, blocking, drawing a
 * bow/crossbow) vanilla clamps movement to ~20% speed.
 *
 * The use-state is taken from the SERVER ({@code isHandRaised()} == Mojang's
 * "isUsingItem"), NOT the spoofable, sticky USE_ITEM packet — a single
 * right-click to place a block / open a door / throw a pearl used to latch the
 * old flag on forever and false-positive normal sprinting. Plus ground +
 * normal-friction + no-knockback gates so only input speed is measured.
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

        boolean usingItem;
        try { usingItem = ctx.player.isHandRaised(); }
        catch (Throwable t) { usingItem = false; }

        if (!usingItem || ctx.unstable()
                || ctx.state.exVehicle || ctx.state.exGliding || ctx.state.exRiptide
                || ctx.state.exLiquid || ctx.state.exWeb || ctx.state.exClimbing) {
            clean(ctx, 1.0);
            return;
        }
        if (!f.simGround || f.groundSlipperiness > 0.61) { clean(ctx, 1.0); return; }
        long now = System.currentTimeMillis();
        if (now - ctx.data.lastVelocityMs < 1200 || now - ctx.data.lastDamageMs < 800
                || now - ctx.data.lastTeleportMs < 1500
                || ctx.state.tick - ctx.state.lastSpecialBlockTick < 12) {
            return;
        }

        double max = cfgD("max-use-speed", 0.135);   // vanilla use-speed ≈ 0.054
        double h = f.horizontalSpeed();
        if (h > max) {
            diverge(ctx, (h - max) * cfgD("score-scale", 50.0),
                    cfgD("threshold", 10.0), cfgI("min-streak", 6),
                    String.format("speed %.3f while using item (max %.2f)", h, max), true);
        } else {
            clean(ctx, 1.5);
        }
    }
}
