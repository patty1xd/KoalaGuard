package com.koalaguard.engine.checks.movement;

import com.koalaguard.KoalaGuard;
import com.koalaguard.check.CheckCategory;
import com.koalaguard.engine.check.CheckContext;
import com.koalaguard.engine.check.SimCheck;
import com.koalaguard.engine.state.PlayerState;
import com.koalaguard.engine.state.PositionFrame;

/**
 * Click-TP / short teleport / flight-warp. A single reconstructed client tick
 * moves further than ANY vanilla state allows (no vehicle/elytra/riptide/
 * knockback/teleport grace active). Unlike PredictionCheck this needs no
 * streak — one impossible jump of several blocks in one tick is conclusive —
 * and it is rubber-banded immediately.
 */
public final class ClickTpCheck extends SimCheck {

    public ClickTpCheck(KoalaGuard plugin) {
        super(plugin, "clicktp", CheckCategory.MOVEMENT, "Impossible single-tick displacement");
    }

    @Override
    public Stage stage() { return Stage.FRAME; }

    @Override
    public void onTick(CheckContext ctx) {
        PlayerState s = ctx.state;
        PositionFrame f = s.current;
        if (f == null) return;

        if (ctx.unstable() || s.exFlying || s.exVehicle || s.exGliding
                || s.exRiptide || s.exLevitation || s.exClimbing || s.exWeb) {
            clean(ctx, 1.0);
            return;
        }
        long now = System.currentTimeMillis();
        if (now - ctx.data.lastVelocityMs < 1500 || now - ctx.data.lastDamageMs < 1000
                || now - ctx.data.lastTeleportMs < 2000 || now - ctx.data.elytraMs < 2000
                || now - ctx.data.slimeBounceMs < 1500 || now - ctx.data.lastSlimeOrBedMs < 1500) {
            return;
        }

        double maxH = cfgD("max-horizontal", 1.2);     // ~4x sprint, way past any input
        double maxV = cfgD("max-vertical", 1.5);
        double h = f.horizontalSpeed();
        if (h > maxH || Math.abs(f.dy) > maxV) {
            diverge(ctx, cfgD("score", 12.0), cfgD("threshold", 10.0),
                    cfgI("min-streak", 1),
                    String.format("teleport dx=%.2f dy=%.2f dz=%.2f", f.dx, f.dy, f.dz),
                    true);
        } else {
            clean(ctx, 3.0);
        }
    }
}
