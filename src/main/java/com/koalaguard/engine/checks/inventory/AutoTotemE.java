package com.koalaguard.engine.checks.inventory;

import com.koalaguard.KoalaGuard;
import com.koalaguard.check.CheckCategory;
import com.koalaguard.engine.check.CheckContext;
import com.koalaguard.engine.check.SimCheck;
import com.koalaguard.engine.state.TotemCycle;
import com.koalaguard.util.MathUtil;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * AutoTotem Type E (TotemGuard) — INDEPENDENT check.
 *
 * Trigger: unrealistically low timing outliers. A bot that fakes "random"
 * delays still has a hard floor it occasionally hits — an extreme low outlier
 * far below the rest of the (otherwise spread) distribution. Pure human play
 * has no such impossible floor.
 */
public final class AutoTotemE extends SimCheck {

    private static final class S {
        long lastSeq = Long.MIN_VALUE;
        final Deque<Double> times = new ArrayDeque<>();
    }

    private final Map<UUID, S> state = new ConcurrentHashMap<>();

    public AutoTotemE(KoalaGuard plugin) {
        super(plugin, "autototeme", CheckCategory.COMBAT, "Impossible totem-timing outlier");
    }

    @Override
    public void onTick(CheckContext ctx) {
        if (ctx.unstableBasic()) return;
        UUID id = ctx.data.getUuid();
        S s = state.computeIfAbsent(id, k -> new S());

        boolean newCycle = false;
        for (TotemCycle c : ctx.state.inv.cycles) {
            if (c.seq <= s.lastSeq) continue;
            s.lastSeq = c.seq;
            s.times.addLast(c.cycleMs);
            while (s.times.size() > cfgI("sample-cap", 30)) s.times.removeFirst();
            newCycle = true;
        }
        if (!newCycle || s.times.size() < cfgI("min-samples", 8)) return;

        double mean = MathUtil.average(s.times);
        double sd = MathUtil.standardDeviation(s.times);
        double min = Double.MAX_VALUE;
        for (double v : s.times) min = Math.min(min, v);

        boolean impossibleFloor = min < cfgD("max-low-ms", 70.0);
        boolean extremeOutlier = sd > 1.0
                && (mean - min) > cfgD("outlier-z", 2.5) * sd;

        if (impossibleFloor && extremeOutlier) {
            if (diverge(ctx, cfgD("score", 7.0), cfgD("threshold", 9.0),
                    cfgI("min-streak", 2),
                    String.format("low outlier min=%.0fms mean=%.0fms sd=%.0fms",
                            min, mean, sd), false)) {
                armCombatCancel(ctx);
            }
        } else {
            clean(ctx, 1.0);
        }
    }

    @Override
    public void cleanup(UUID uuid) {
        super.cleanup(uuid);
        state.remove(uuid);
    }
}
