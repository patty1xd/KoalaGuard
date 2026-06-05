package com.koalaguard.engine.checks.movement;

import com.koalaguard.KoalaGuard;
import com.koalaguard.check.CheckCategory;
import com.koalaguard.engine.check.CheckContext;
import com.koalaguard.engine.check.SimCheck;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Timer / game-speed. The reference clock is NOT System.currentTimeMillis — it
 * is the confirmed-transaction counter, an authoritative server clock the
 * client cannot speed up and that moves at exactly the same rate as real
 * server ticks (so server lag affects both sides equally and can never be
 * mistaken for a timer). Over a window we compare reconstructed client
 * movement ticks against confirmed server ticks; a sustained ratio above 1 is
 * physically only possible by accelerating the client's tick loop.
 */
public final class TimerCheck extends SimCheck {

    private static final class W {
        long baseConf = -1, baseTick;
        long accConf, accFrames;
        // Per-bucket frame counters for timer-balance detection: split the
        // window into N equal-sized confirmed-transaction buckets and check
        // for burst concentration (a fast-then-slow alternation that averages
        // to ratio ≈1 still exhibits one or two buckets carrying most frames).
        final long[] bucketFrames = new long[6];
        int bucketIdx;
        long bucketBaseConf = -1;
    }

    private final Map<UUID, W> win = new ConcurrentHashMap<>();

    public TimerCheck(KoalaGuard plugin) {
        super(plugin, "timer", CheckCategory.MOVEMENT, "Client tick acceleration (timer)");
    }

    @Override
    public void onTick(CheckContext ctx) {
        if (ctx.unstable()) return;
        UUID id = ctx.data.getUuid();
        W w = win.computeIfAbsent(id, k -> new W());

        long conf = ctx.data.confirmedTransactions;
        long tick = ctx.state.tick;
        if (w.baseConf < 0) { w.baseConf = conf; w.baseTick = tick; return; }

        long dConf = conf - w.baseConf;
        if (dConf <= 0) return;                     // no new server tick yet
        long dFrames = tick - w.baseTick;
        w.baseConf = conf; w.baseTick = tick;

        w.accConf += dConf;
        w.accFrames += dFrames;

        // Bucket frames by confirmed-transaction span (each bucket = window/N
        // confirmed ticks). Timer-balance cheats put all the fast-tick frames
        // in one or two buckets and zero in the rest; the overall ratio looks
        // fine but the distribution is skewed.
        if (w.bucketBaseConf < 0) w.bucketBaseConf = conf;
        int bucketsPerWindow = w.bucketFrames.length;
        int bucketSpan = Math.max(1, cfgI("window-ticks", 60) / bucketsPerWindow);
        while (conf - w.bucketBaseConf >= bucketSpan) {
            w.bucketIdx = (w.bucketIdx + 1) % bucketsPerWindow;
            w.bucketFrames[w.bucketIdx] = 0;
            w.bucketBaseConf += bucketSpan;
        }
        w.bucketFrames[w.bucketIdx] += dFrames;

        int window = cfgI("window-ticks", 60);
        if (w.accConf < window) return;

        // KEY GATE: s.tick (the movement-tick counter) FREEZES while the
        // player is stationary (rotation-only packets create no PositionFrame,
        // so `tick - baseTick` accumulates ZERO frames per server tick). A
        // ratio-based comparison vs confirmed-transactions therefore gives
        // ratio = 0 for any non-moving player. The previous slow-timer
        // variant was triggering on EVERY standing-still player and on
        // anyone moving sparsely — that's why it false-alerts for "doing
        // literally anything". Only evaluate when we have enough MOVEMENT
        // frames in the window for the ratio to be meaningful.
        int minMoveFrames = cfgI("min-move-frames", 30);
        long accFrames = w.accFrames;
        long accConf   = w.accConf;
        w.accConf = 0; w.accFrames = 0;

        if (accFrames < minMoveFrames) {
            // Player isn't actively moving — timer-by-frame-ratio is undefined.
            // Reset the accumulator and bleed off any prior score so the
            // window doesn't carry stale data into a fight.
            clean(ctx, 3.0);
            return;
        }

        double ratio = (double) accFrames / (double) accConf;
        double eps = cfgD("max-ratio-excess", 0.18);

        if (ratio > 1.0 + eps) {
            diverge(ctx, (ratio - 1.0) * cfgD("score-scale", 40.0),
                    cfgD("threshold", 10.0), cfgI("min-streak", 2),
                    String.format("tick ratio %.3f over %d server ticks (fast timer)", ratio, window), true);
            return;
        }

        // Timer-balance: total ratio looks legal but the per-bucket
        // distribution is wildly skewed. Compute the max-bucket fraction
        // and flag if one bucket holds more than half of the window's
        // frames AND the average pace is meaningfully fast.
        long maxBucket = 0, totalBucket = 0;
        for (long b : w.bucketFrames) { totalBucket += b; if (b > maxBucket) maxBucket = b; }
        if (totalBucket > 0 && accFrames >= minMoveFrames) {
            double maxFrac = (double) maxBucket / totalBucket;
            if (maxFrac > cfgD("max-bucket-frac", 0.55)
                    && ratio > 1.0 + cfgD("balance-min-ratio-excess", 0.05)) {
                diverge(ctx, cfgD("balance-score", 5.0),
                        cfgD("threshold", 10.0), cfgI("balance-min-streak", 2),
                        String.format("timer-balance: peak bucket %.0f%% of frames, ratio %.3f",
                                maxFrac * 100, ratio), true);
                return;
            }
        }
        {
            // Slow-timer detection is deliberately NOT done here — movement-
            // tick-count vs confirmed-transactions intrinsically goes below
            // 1.0 for any player whose FPS is below the server rate, for
            // anyone walking-and-stopping, and for anyone with packet jitter.
            // It's not a usable signal at the engine's metric level. A real
            // slow-timer cheat is rare and easier to spot via late attacks /
            // late inventory clicks; falsely flagging legit play here is the
            // bigger harm.
            clean(ctx, 3.0);
        }
    }

    @Override
    public void cleanup(UUID uuid) {
        super.cleanup(uuid);
        win.remove(uuid);
    }
}
