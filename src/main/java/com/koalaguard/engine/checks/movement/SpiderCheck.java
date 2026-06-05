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

        // Vanilla jump arc against a corner: at airTicks=5..7 dy can still be
        // ~0.08-0.12 (apex not yet reached for a sprint-jump). The old
        // min-up=0.02 caught the entire upward half of every corner jump.
        // Require both a higher dy floor AND deeper airTicks so the check only
        // fires when the player is ALREADY past the natural apex but still
        // rising — vanilla can't do that.
        boolean climbing = f.dy > cfgD("min-up", 0.10) && s.airTicks > cfgI("min-air-ticks", 8);
        boolean wall = CollisionEngine.touchingWall(ctx.player.getWorld(), f.x, f.y, f.z);

        if (climbing && wall) {
            diverge(ctx, cfgD("score", 5.0), cfgD("threshold", 10.0),
                    cfgI("min-streak", 5),
                    String.format("wall-climb dy=%.3f airTicks=%d", f.dy, s.airTicks),
                    true);
            return;
        }

        // Pulse-spider: micro-climbs that stay under min-up per tick but sum
        // to a real vertical net over a short window while pressed against a
        // wall. Vanilla cannot push the player up against a sheer wall via
        // any sustained micro-impulse.
        //
        // CRITICAL FP guard: only evaluate once the player has been airborne
        // well past the natural jump arc (airTicks > min-air-ticks). A normal
        // jump beside a wall produces several positive-dy frames summing far
        // over the threshold; by 8+ airborne ticks a real jump is already
        // FALLING, so its positive-dy sum is tiny — only a genuine sustained
        // wall-climb keeps gaining height.
        if (wall && !f.simGround && s.airTicks > cfgI("pulse-min-air-ticks", 8)) {
            double sumUp = 0;
            int upTicks = 0, totalTicks = 0;
            for (PositionFrame g : s.recentFrames(cfgI("pulse-window", 6))) {
                totalTicks++;
                if (g.dy > 0.02) { sumUp += g.dy; upTicks++; }
                else if (g.simGround) { sumUp = 0; upTicks = 0; break; }
            }
            if (totalTicks >= cfgI("pulse-min-frames", 5)
                    && sumUp > cfgD("pulse-min-sum", 0.35)
                    && upTicks >= cfgI("pulse-min-up-ticks", 3)) {
                diverge(ctx, cfgD("pulse-score", 4.0), cfgD("threshold", 10.0),
                        cfgI("pulse-min-streak", 3),
                        String.format("pulse-spider: sum dy=%.3f over %d/%d frames against wall",
                                sumUp, upTicks, totalTicks), true);
                return;
            }
        }
        clean(ctx, 2.0);
    }
}
