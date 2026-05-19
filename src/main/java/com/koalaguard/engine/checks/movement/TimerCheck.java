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

        int window = cfgI("window-ticks", 60);
        if (w.accConf < window) return;

        double ratio = (double) w.accFrames / (double) w.accConf;
        double eps = cfgD("max-ratio-excess", 0.18);
        w.accConf = 0; w.accFrames = 0;

        if (ratio > 1.0 + eps) {
            diverge(ctx, (ratio - 1.0) * cfgD("score-scale", 40.0),
                    cfgD("threshold", 10.0), cfgI("min-streak", 2),
                    String.format("tick ratio %.3f over %d server ticks", ratio, window), true);
        } else {
            clean(ctx, 3.0);
        }
    }

    @Override
    public void cleanup(UUID uuid) {
        super.cleanup(uuid);
        win.remove(uuid);
    }
}
