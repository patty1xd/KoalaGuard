package com.koalaguard.engine.checks.movement;

import com.koalaguard.KoalaGuard;
import com.koalaguard.check.CheckCategory;
import com.koalaguard.engine.check.CheckContext;
import com.koalaguard.engine.check.SimCheck;
import com.koalaguard.engine.state.PositionFrame;

/**
 * Phase / NoClip. Purely geometric: the reconstructed body box is inside solid
 * collision geometry. One tick inside a block can be a placement race or the
 * slab/stairs outer-box approximation, so a SINGLE intersection never flags —
 * only the player remaining embedded across consecutive reconstructed ticks
 * (an impossible vanilla state) is confirmed, then set back.
 */
public final class PhaseCheck extends SimCheck {

    public PhaseCheck(KoalaGuard plugin) {
        super(plugin, "phase", CheckCategory.MOVEMENT, "Moving through solid blocks");
    }

    @Override
    public Stage stage() { return Stage.FRAME; }

    @Override
    public void onTick(CheckContext ctx) {
        PositionFrame f = ctx.state.current;
        if (f == null) return;

        if (ctx.unstable() || ctx.state.exVehicle || ctx.state.exGliding) {
            clean(ctx, 1.0);
            return;
        }

        if (f.insideSolid) {
            diverge(ctx, cfgD("score-per-tick", 6.0),
                    cfgD("threshold", 14.0), cfgI("min-streak", 3),
                    String.format("inside solid @ %.2f,%.2f,%.2f (streak=%d)",
                            f.x, f.y, f.z, score(ctx).badStreak()), true);
        } else {
            clean(ctx, 4.0);
        }
    }
}
