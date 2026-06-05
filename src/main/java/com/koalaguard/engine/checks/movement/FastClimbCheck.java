package com.koalaguard.engine.checks.movement;

import com.koalaguard.KoalaGuard;
import com.koalaguard.check.CheckCategory;
import com.koalaguard.engine.check.CheckContext;
import com.koalaguard.engine.check.SimCheck;
import com.koalaguard.engine.state.PositionFrame;

/**
 * FastClimb / LadderFly. Vanilla caps vertical speed on a ladder/vine at
 * ~0.118 up (0.15 down). Climbing faster is impossible. Only evaluated while
 * the server confirms the player is on a climbable, so it cannot false-positive
 * normal movement.
 */
public final class FastClimbCheck extends SimCheck {

    public FastClimbCheck(KoalaGuard plugin) {
        super(plugin, "fastclimb", CheckCategory.MOVEMENT, "Climbing faster than vanilla allows");
    }

    @Override
    public Stage stage() { return Stage.FRAME; }

    @Override
    public void onTick(CheckContext ctx) {
        PositionFrame f = ctx.state.current;
        if (f == null) return;
        if (!ctx.state.exClimbing || ctx.unstable()
                || ctx.state.exVehicle || ctx.state.exLevitation || ctx.state.exRiptide) {
            clean(ctx, 1.0);
            return;
        }
        long now = System.currentTimeMillis();
        // Added teleport / bubble-column / slime-bounce grace. The previous
        // version only graced kb and damage; a /tp landing on a ladder
        // mid-fall, or a bubble-column eject onto a ladder, or a slime
        // bounce that finished on a ladder all carry forward >0.40 dy for
        // a frame or two and false-flagged climb-down.
        if (now - ctx.data.lastVelocityMs < 1200 || now - ctx.data.lastDamageMs < 800
                || now - ctx.data.lastTeleportMs < 1500
                || now - ctx.data.bubbleColumnMs < 1500
                || now - ctx.data.slimeBounceMs < 1500
                || now - ctx.data.lastSlimeOrBedMs < 1500) return;

        double maxUp   = cfgD("max-up", 0.24);
        double maxDown = cfgD("max-down", 0.40);
        if (f.dy > maxUp) {
            diverge(ctx, (f.dy - maxUp) * cfgD("score-scale", 30.0),
                    cfgD("threshold", 8.0), cfgI("min-streak", 4),
                    String.format("climb up dy=%.3f > %.2f", f.dy, maxUp), true);
            return;
        } else if (-f.dy > maxDown) {
            diverge(ctx, (-f.dy - maxDown) * cfgD("score-scale", 30.0),
                    cfgD("threshold", 8.0), cfgI("min-streak", 4),
                    String.format("climb down dy=%.3f", f.dy), true);
            return;
        }

        // Pulse-climb: cheats alternate fast (~0.25) and slow (~0.05) ticks
        // so per-tick stays under the cap while the AVERAGE climb rate is
        // ~0.15 (above vanilla 0.118). Sum dy over recent climb-ticks; flag
        // when average exceeds vanilla cap by a clear margin.
        double sum = 0;
        int n = 0;
        for (PositionFrame g : ctx.state.recentFrames(cfgI("avg-window", 10))) {
            if (g.dy > 0) { sum += g.dy; n++; }
        }
        if (n >= cfgI("min-avg-frames", 8)) {
            double avg = sum / n;
            double vanillaCap = cfgD("vanilla-up-cap", 0.118);
            double margin = cfgD("avg-margin", 0.02);
            if (avg > vanillaCap + margin) {
                diverge(ctx, (avg - vanillaCap) * cfgD("avg-score-scale", 25.0),
                        cfgD("threshold", 8.0), cfgI("avg-min-streak", 3),
                        String.format("pulse-climb: avg up dy=%.3f over %d ticks > %.3f",
                                avg, n, vanillaCap), true);
                return;
            }
        }
        clean(ctx, 2.0);
    }
}
