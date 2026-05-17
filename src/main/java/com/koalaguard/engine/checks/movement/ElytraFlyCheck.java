package com.koalaguard.engine.checks.movement;

import com.koalaguard.KoalaGuard;
import com.koalaguard.check.CheckCategory;
import com.koalaguard.engine.check.CheckContext;
import com.koalaguard.engine.check.SimCheck;
import com.koalaguard.engine.packet.PacketKind;
import com.koalaguard.engine.state.PlayerState;
import com.koalaguard.engine.state.PositionFrame;

/**
 * ElytraFly. Real elytra cannot gain sustained altitude without a firework
 * boost — it always trades height for speed (gravity-bound glide). Climbing
 * upward for many consecutive ticks while gliding, with no recent rocket
 * (USE_ITEM), is an elytra-fly exploit. Diving→pull-up is excluded by the
 * sustained-streak requirement.
 */
public final class ElytraFlyCheck extends SimCheck {

    public ElytraFlyCheck(KoalaGuard plugin) {
        super(plugin, "elytrafly", CheckCategory.MOVEMENT, "Powered/sustained-climb elytra");
    }

    @Override
    public Stage stage() { return Stage.FRAME; }

    @Override
    public void onTick(CheckContext ctx) {
        PlayerState s = ctx.state;
        PositionFrame f = s.current;
        if (f == null) return;
        if (!s.exGliding || ctx.unstable() || s.exVehicle
                || s.exLevitation || s.exRiptide) {
            clean(ctx, 1.0);
            return;
        }
        long now = System.currentTimeMillis();
        if (now - ctx.data.lastVelocityMs < 1500) return;

        // Recent firework boost legitimately allows a climb.
        boolean rocket = ctx.state.log.recent(40).stream()
                .anyMatch(p -> p.kind == PacketKind.USE_ITEM
                        && (System.nanoTime() - p.recvNanos) / 1_000_000L
                            < cfgL("rocket-window-ms", 1500L));
        if (rocket) { clean(ctx, 1.0); return; }

        if (f.dy > cfgD("max-climb-dy", 0.06)) {
            diverge(ctx, (f.dy) * cfgD("score-scale", 30.0),
                    cfgD("threshold", 12.0), cfgI("min-streak", 8),
                    String.format("elytra climb dy=%.3f no rocket", f.dy), true);
        } else {
            clean(ctx, 1.0);
        }
    }
}
