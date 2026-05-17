package com.koalaguard.engine.checks.movement;

import com.koalaguard.KoalaGuard;
import com.koalaguard.check.CheckCategory;
import com.koalaguard.engine.check.CheckContext;
import com.koalaguard.engine.check.SimCheck;
import com.koalaguard.engine.sim.SimResult;
import com.koalaguard.engine.state.PlayerState;
import com.koalaguard.engine.state.PositionFrame;

/**
 * The flagship movement check. Per reconstructed client tick it compares the
 * ACTUAL motion to the simulator's vanilla envelope and accumulates the
 * divergence. It deliberately replaces Speed/Fly/Motion/Glide as separate
 * threshold checks: there is no "max blocks/s" anywhere — only "did the player
 * move further/higher than vanilla physics allows, and did they keep doing
 * it". A single tick over the envelope is ignored; persistent divergence is
 * confirmed and the player is rubber-banded to the last sim-valid position.
 */
public final class PredictionCheck extends SimCheck {

    public PredictionCheck(KoalaGuard plugin) {
        super(plugin, "prediction", CheckCategory.MOVEMENT,
                "Predicted vs actual physics divergence");
    }

    @Override
    public Stage stage() { return Stage.FRAME; }

    @Override
    public void onTick(CheckContext ctx) {
        PlayerState s = ctx.state;
        PositionFrame f = s.current;
        SimResult sim = ctx.sim;
        if (f == null || s.previous == null) return;

        if (ctx.unstable() || exempt(s) || verticalUnsafe(ctx)) {
            clean(ctx, 0.5);
            return;
        }

        double hEps = cfgD("horizontal-epsilon", 0.0015);
        double vEps = cfgD("vertical-epsilon", 0.02);
        double scale = cfgD("score-scale", 24.0);
        double threshold = cfgD("threshold", 8.0);
        int minStreak = cfgI("min-streak", 6);

        double bad = 0;
        StringBuilder why = new StringBuilder();

        // Horizontal: only an EXCESS over the vanilla envelope is suspicious.
        if (sim.horizontalReliable && !f.collidedHorizontally) {
            double actualH = f.horizontalSpeed();
            double over = actualH - sim.maxHorizontal;
            if (over > hEps) {
                bad += over * scale;
                why.append(String.format("h=%.4f>%.4f ", actualH, sim.maxHorizontal));
            }
        }

        // Vertical: actual dY outside the simulated band (fly / motion / step).
        if (sim.verticalReliable && !f.simGround && !f.collidedVertically) {
            double dy = f.dy;
            if (dy > sim.dyHigh + vEps) {
                bad += (dy - sim.dyHigh) * scale * 1.5;
                why.append(String.format("dy=%.4f>%.4f ", dy, sim.dyHigh));
            } else if (dy < sim.dyLow - vEps) {
                bad += (sim.dyLow - dy) * scale;
                why.append(String.format("dy=%.4f<%.4f ", dy, sim.dyLow));
            }
        }

        if (bad > 0) {
            diverge(ctx, bad, threshold, minStreak,
                    "physics divergence " + why.toString().trim()
                            + " (streak=" + score(ctx).badStreak() + ")", true);
        } else {
            clean(ctx, 1.0);
        }
    }

    private boolean exempt(PlayerState s) {
        // Cobweb/powder-snow/berry/honey/scaffolding clamp velocity in ways
        // the simulator doesn't model — exempt while inside AND for a short
        // grace after leaving (residual reduced motion + block-edge slack).
        boolean webGrace = s.tick - s.lastSpecialBlockTick
                < cfgI("special-block-grace-ticks", 12);
        return s.exFlying || s.exVehicle || s.exGliding || s.exClimbing
                || s.exLiquid || s.exRiptide || s.exLevitation || s.exSlowFalling
                || s.exWeb || webGrace;
    }

    /** External vertical forces the simulator does not model precisely. */
    private boolean verticalUnsafe(CheckContext ctx) {
        long now = System.currentTimeMillis();
        var d = ctx.data;
        return now - d.lastVelocityMs   < 1200
            || now - d.lastDamageMs     < 800
            || now - d.slimeBounceMs    < 1500
            || now - d.bubbleColumnMs   < 1200
            || now - d.elytraMs         < 1500
            || now - d.lastSlimeOrBedMs < 1200
            || now - d.lastTeleportMs   < 1500;
    }
}
