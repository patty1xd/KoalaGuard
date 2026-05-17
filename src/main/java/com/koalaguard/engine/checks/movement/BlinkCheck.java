package com.koalaguard.engine.checks.movement;

import com.koalaguard.KoalaGuard;
import com.koalaguard.check.CheckCategory;
import com.koalaguard.engine.check.CheckContext;
import com.koalaguard.engine.check.SimCheck;

/**
 * Blink / lag-switch. The client withholds movement packets, then releases the
 * whole buffered batch at once — appearing as an impossible BURST of movement
 * frames replayed in a single server tick. A laggy connection produces at most
 * a couple; a Blink release produces many. Gated by the lag model so genuine
 * latency spikes cannot false-positive.
 */
public final class BlinkCheck extends SimCheck {

    public BlinkCheck(KoalaGuard plugin) {
        super(plugin, "blink", CheckCategory.MOVEMENT, "Withheld then burst-released movement");
    }

    @Override
    public void onTick(CheckContext ctx) {
        if (ctx.data.lag.unstable()) { clean(ctx, 0.5); return; }
        int burst = ctx.state.framesThisTick;
        int max = cfgI("max-frames-per-tick", 9);
        if (burst >= max) {
            diverge(ctx, (burst - max + 1) * cfgD("score-scale", 3.0),
                    cfgD("threshold", 9.0), cfgI("min-streak", 2),
                    burst + " movement frames in one tick (blink release)", true);
        } else {
            clean(ctx, 1.5);
        }
    }
}
