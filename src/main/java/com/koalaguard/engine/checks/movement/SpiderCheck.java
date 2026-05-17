package com.koalaguard.engine.checks.movement;

import com.koalaguard.KoalaGuard;
import com.koalaguard.check.CheckCategory;
import com.koalaguard.engine.check.CheckContext;
import com.koalaguard.engine.check.SimCheck;
import com.koalaguard.engine.sim.CollisionEngine;
import com.koalaguard.engine.state.PlayerState;
import com.koalaguard.engine.state.PositionFrame;

/**
 * Spider / wall-climb. The player gains height while pressed against a wall
 * with no climbable (ladder/vine), not on scaffolding/honey, and well past the
 * initial jump arc. Vanilla cannot ascend a sheer wall. Persistence required.
 */
public final class SpiderCheck extends SimCheck {

    public SpiderCheck(KoalaGuard plugin) {
        super(plugin, "spider", CheckCategory.MOVEMENT, "Climbing a wall without a climbable");
    }

    @Override
    public Stage stage() { return Stage.FRAME; }

    @Override
    public void onTick(CheckContext ctx) {
        PlayerState s = ctx.state;
        PositionFrame f = s.current;
        if (f == null) return;
        if (ctx.unstable() || s.exClimbing || s.exWeb || s.exFlying || s.exGliding
                || s.exVehicle || s.exLevitation || s.exRiptide || s.exLiquid
                || f.simGround) {
            clean(ctx, 1.0);
            return;
        }
        long now = System.currentTimeMillis();
        if (now - ctx.data.lastVelocityMs < 1200 || now - ctx.data.lastDamageMs < 800
                || now - ctx.data.bubbleColumnMs < 1500 || now - ctx.data.slimeBounceMs < 1500) {
            return;
        }

        boolean climbing = f.dy > cfgD("min-up", 0.02) && s.airTicks > cfgI("min-air-ticks", 4);
        boolean wall = CollisionEngine.touchingWall(ctx.player.getWorld(), f.x, f.y, f.z);

        if (climbing && wall) {
            diverge(ctx, cfgD("score", 5.0), cfgD("threshold", 10.0),
                    cfgI("min-streak", 5),
                    String.format("wall-climb dy=%.3f airTicks=%d", f.dy, s.airTicks),
                    true);
        } else {
            clean(ctx, 2.0);
        }
    }
}
