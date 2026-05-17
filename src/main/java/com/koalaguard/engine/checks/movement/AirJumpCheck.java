package com.koalaguard.engine.checks.movement;

import com.koalaguard.KoalaGuard;
import com.koalaguard.check.CheckCategory;
import com.koalaguard.engine.check.CheckContext;
import com.koalaguard.engine.check.SimCheck;
import com.koalaguard.engine.state.PlayerState;
import com.koalaguard.engine.state.PositionFrame;

/**
 * AirJump / multi-jump. A fresh upward jump impulse can only happen from the
 * ground. If the vertical velocity suddenly flips from falling/flat to a
 * jump-sized positive value while the player has been airborne for several
 * ticks (no ground, no near-ground), that is an impossible second jump.
 * Slime/bed/knockback/levitation/web/elytra are all exempt so it cannot
 * false-positive.
 */
public final class AirJumpCheck extends SimCheck {

    public AirJumpCheck(KoalaGuard plugin) {
        super(plugin, "airjump", CheckCategory.MOVEMENT, "Jumping in mid-air");
    }

    @Override
    public Stage stage() { return Stage.FRAME; }

    @Override
    public void onTick(CheckContext ctx) {
        PlayerState s = ctx.state;
        PositionFrame f = s.current, prev = s.previous;
        if (f == null || prev == null) return;

        if (ctx.unstable() || s.exFlying || s.exGliding || s.exClimbing
                || s.exLiquid || s.exLevitation || s.exSlowFalling
                || s.exRiptide || s.exVehicle || s.exWeb) {
            clean(ctx, 1.0);
            return;
        }
        long now = System.currentTimeMillis();
        if (now - ctx.data.lastVelocityMs < 1200 || now - ctx.data.lastDamageMs < 800
                || now - ctx.data.slimeBounceMs < 1500 || now - ctx.data.lastSlimeOrBedMs < 1500
                || now - ctx.data.bubbleColumnMs < 1200 || now - ctx.data.lastTeleportMs < 1500
                || s.tick - s.lastSpecialBlockTick < 12) {
            return;
        }

        double impulse = cfgD("min-jump-impulse", 0.30);
        boolean freshJump = f.dy > impulse && prev.dy <= 0.0;
        boolean airborne  = !f.simGround && s.airTicks > cfgI("min-air-ticks", 4);

        if (freshJump && airborne) {
            diverge(ctx, cfgD("score", 6.0), cfgD("threshold", 9.0),
                    cfgI("min-streak", 2),
                    String.format("air jump dy=%.3f after %d air ticks", f.dy, s.airTicks),
                    true);
        } else {
            clean(ctx, 2.0);
        }
    }
}
