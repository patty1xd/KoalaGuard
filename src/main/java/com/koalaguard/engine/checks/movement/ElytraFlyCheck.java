package com.koalaguard.engine.checks.movement;

import com.koalaguard.KoalaGuard;
import com.koalaguard.check.CheckCategory;
import com.koalaguard.engine.check.CheckContext;
import com.koalaguard.engine.check.SimCheck;
import com.koalaguard.engine.packet.PacketKind;
import com.koalaguard.engine.state.PositionFrame;

/**
 * ElytraFly — DISABLED BY DEFAULT.
 *
 * Real elytra gliding legitimately includes upward motion (pitch up to trade
 * speed for height) and firework boosts, so a naive "climbing" rule
 * false-positives normal flight (exactly what was reported). Detecting it
 * properly needs the full vanilla elytra glide integration; until that is
 * modelled this only flags the blatant, physically-impossible case: a long
 * SUSTAINED climb with NO firework anywhere in a generous window — you cannot
 * keep gaining height on an elytra without a rocket (you stall). Leave
 * disabled unless you accept the residual risk.
 */
public final class ElytraFlyCheck extends SimCheck {

    public ElytraFlyCheck(KoalaGuard plugin) {
        super(plugin, "elytrafly", CheckCategory.MOVEMENT, "Sustained rocketless elytra climb");
    }

    @Override
    public Stage stage() { return Stage.FRAME; }

    @Override
    public void onTick(CheckContext ctx) {
        PositionFrame f = ctx.state.current;
        if (f == null) return;
        if (!ctx.state.exGliding || ctx.unstable()
                || ctx.state.exVehicle || ctx.state.exLevitation || ctx.state.exRiptide) {
            clean(ctx, 1.0);
            return;
        }
        long now = System.currentTimeMillis();
        if (now - ctx.data.lastVelocityMs < 2000) return;

        // Any recent right-click (firework) within a generous window exempts.
        boolean rocket = ctx.state.log.recent(80).stream().anyMatch(p ->
                (p.kind == PacketKind.USE_ITEM || p.kind == PacketKind.BLOCK_PLACE)
                && (System.nanoTime() - p.recvNanos) / 1_000_000L
                    < cfgL("rocket-window-ms", 3000L));
        if (rocket) { clean(ctx, 2.0); return; }

        if (f.dy > cfgD("min-climb-dy", 0.15)) {
            diverge(ctx, cfgD("score", 3.0), cfgD("threshold", 12.0),
                    cfgI("min-streak", 25),
                    String.format("rocketless elytra climb dy=%.3f", f.dy), true);
        } else {
            clean(ctx, 2.0);
        }
    }
}
