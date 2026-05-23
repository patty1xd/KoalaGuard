package com.koalaguard.engine.checks.player;

import com.koalaguard.KoalaGuard;
import com.koalaguard.check.CheckCategory;
import com.koalaguard.engine.check.CheckContext;
import com.koalaguard.engine.check.SimCheck;

/**
 * SprintHunger. Vanilla rule: a player may only START sprinting when
 * {@code foodLevel >= 6} (the client's own input handler enforces this).
 * A cheat that ignores this restriction lets the player sprint with low /
 * zero hunger — useful for surviving anarchy starvation, long bridges, and
 * post-damage chase scenarios.
 *
 * Detection: server-authoritative. Reads {@code Player.getFoodLevel()} and
 * compares against the netty-thread sprinting flag plus actual movement
 * speed. The food value is server-controlled (the client cannot spoof it
 * — hunger is updated by the server via the food packet; client-side
 * spoofs are reverted next tick), so the cheat cannot hide.
 *
 * FP-safe: requires BOTH the sprinting flag AND a real sprint-magnitude
 * speed for several ticks — a single tick of momentum carry-over from a
 * previous legitimate sprint won't trip it. Vehicle / gliding / riptide /
 * special-block exempt.
 */
public final class SprintHungerCheck extends SimCheck {

    public SprintHungerCheck(KoalaGuard plugin) {
        super(plugin, "sprinthunger", CheckCategory.PLAYER,
                "Sprinting while food level too low (vanilla forbids)");
    }

    @Override
    public void onTick(CheckContext ctx) {
        if (ctx.unstableBasic()) return;
        if (ctx.state.exFlying || ctx.state.exVehicle || ctx.state.exGliding
                || ctx.state.exRiptide || ctx.state.exLevitation
                || ctx.state.exLiquid) {
            clean(ctx, 1.0);
            return;
        }
        long now = System.currentTimeMillis();
        if (now - ctx.data.lastVelocityMs < 1500
                || now - ctx.data.lastDamageMs < 800
                || now - ctx.data.lastTeleportMs < 1500
                || ctx.state.tick - ctx.state.lastSpecialBlockTick < 12) {
            return;
        }

        int food;
        try { food = ctx.player.getFoodLevel(); }
        catch (Throwable t) { return; }
        int minFood = cfgI("min-food-to-sprint", 6);
        if (food >= minFood) { clean(ctx, 1.0); return; }

        boolean sprinting = ctx.state.sprinting;
        double speed = ctx.state.current != null ? ctx.state.current.horizontalSpeed() : 0;
        double minSpeed = cfgD("min-sprint-speed", 0.20);
        if (!sprinting || speed < minSpeed) { clean(ctx, 0.5); return; }

        diverge(ctx, cfgD("score", 6.0), cfgD("threshold", 9.0),
                cfgI("min-streak", 4),
                String.format("sprinting with food=%d (vanilla cap %d) speed=%.3f",
                        food, minFood, speed),
                false);
    }
}
