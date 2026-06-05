package com.koalaguard.engine.checks.movement;

import com.koalaguard.KoalaGuard;
import com.koalaguard.check.CheckCategory;
import com.koalaguard.engine.check.CheckContext;
import com.koalaguard.engine.check.SimCheck;
import com.koalaguard.engine.sim.CollisionEngine;
import com.koalaguard.engine.state.PositionFrame;

/**
 * AntiVoid. Over the void with no support anywhere below, vanilla physics
 * REQUIRES the player to keep accelerating downward (terminal velocity). An
 * AntiVoid holds them at a Y or eases the fall. Detected as: deep in the air,
 * no ground within a long scan, yet the downward velocity is implausibly small
 * for several ticks. Nothing legitimate hovers in the void (flight/elytra/
 * levitation/riptide are all exempt).
 */
public final class AntiVoidCheck extends SimCheck {

    public AntiVoidCheck(KoalaGuard plugin) {
        super(plugin, "antivoid", CheckCategory.MOVEMENT, "Cancelled void fall");
    }

    @Override
    public Stage stage() { return Stage.FRAME; }

    @Override
    public void onTick(CheckContext ctx) {
        PositionFrame f = ctx.state.current;
        if (f == null) return;
        if (ctx.unstable() || ctx.state.exFlying || ctx.state.exGliding
                || ctx.state.exVehicle || ctx.state.exLevitation
                || ctx.state.exRiptide || ctx.state.exSlowFalling
                || ctx.state.exClimbing || ctx.state.exWeb || ctx.state.exLiquid) {
            clean(ctx, 1.0);
            return;
        }
        long now = System.currentTimeMillis();
        if (now - ctx.data.lastVelocityMs < 1500 || now - ctx.data.lastTeleportMs < 2000
                || now - ctx.data.slimeBounceMs < 1500 || now - ctx.data.lastSlimeOrBedMs < 1500) {
            return;
        }

        double h = ctx.player.isSneaking() ? 1.5 : 1.8;
        // No support for a long way down (true void / antivoid hover). Step
        // at 0.5 blocks instead of 1.0 so a slab/carpet floor 5.5 blocks
        // below is not skipped (previously stepping 5.0→6.0 jumped over a
        // 0.5-block-thick floor and concluded "no support" — produced FPs on
        // top of high towers and slab-edged builds).
        boolean groundBelow = false;
        for (double d = 0.0; d <= 6.0 && !groundBelow; d += 0.5) {
            groundBelow = CollisionEngine.supported(ctx.player.getWorld(), f.x, f.y - d, f.z, h);
        }
        if (groundBelow) { clean(ctx, 1.0); return; }

        // Falling with no ground => |dy| must grow toward terminal velocity.
        if (ctx.state.airTicks > cfgI("min-air-ticks", 10) && f.dy > cfgD("min-fall-dy", -0.15)) {
            diverge(ctx, cfgD("score", 5.0), cfgD("threshold", 12.0),
                    cfgI("min-streak", 6),
                    String.format("void hover dy=%.3f airTicks=%d", f.dy, ctx.state.airTicks),
                    true);
            return;
        }

        // Pulse / burst-descent variant: cheat alternates between real falls
        // (-0.5+) and hover ticks (~0). Detect by tracking the fraction of
        // recent air-ticks where |dy| is tiny — a real void fall produces
        // monotonically growing |dy|, never returns to near-zero.
        int hoverTicks = 0, recentN = 0;
        for (PositionFrame g : ctx.state.recentFrames(20)) {
            if (g.simGround) break;
            recentN++;
            if (g.dy > -0.10 && g.dy < 0.10) hoverTicks++;
        }
        if (recentN >= cfgI("pulse-min-airframes", 12)
                && hoverTicks >= cfgI("pulse-min-hover-ticks", 4)) {
            diverge(ctx, cfgD("pulse-score", 4.0), cfgD("threshold", 12.0),
                    cfgI("pulse-min-streak", 4),
                    String.format("void pulse-hover: %d hover ticks in %d airframes",
                            hoverTicks, recentN),
                    true);
            return;
        }
        clean(ctx, 1.0);
    }
}
