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
            return;
        }

        // Pulse-blink: 0-tick gaps (silent ticks) alternating with mini-burst
        // (3-4 frames) ticks. Track the trailing window of framesThisTick over
        // ~12 ticks; flag when the silent fraction is high AND mini-burst
        // ticks appear regularly. A genuine micro-stutter LAN spike doesn't
        // produce this — it's one stutter, not a repeating drip.
        int idx = (int) (ctx.state.tick % 12);
        if (ctx.state.blinkPulse == null) ctx.state.blinkPulse = new int[12];
        ctx.state.blinkPulse[idx] = burst;
        int silent = 0, miniBurst = 0;
        for (int v : ctx.state.blinkPulse) {
            if (v == 0) silent++;
            else if (v >= 3 && v < max) miniBurst++;
        }
        if (silent >= cfgI("pulse-min-silent", 5)
                && miniBurst >= cfgI("pulse-min-mini-burst", 3)) {
            diverge(ctx, cfgD("pulse-score", 4.0), cfgD("threshold", 9.0),
                    cfgI("pulse-min-streak", 3),
                    String.format("pulse-blink: %d silent / %d mini-burst ticks in 12",
                            silent, miniBurst),
                    true);
            return;
        }
        clean(ctx, 1.5);
    }
}
