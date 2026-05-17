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
 * AutoTotem Type B (TotemGuard) — INDEPENDENT check.
 *
 * Trigger: unnaturally low standard deviation in re-equip timings. Legitimate
 * players never re-equip at nearly identical intervals; an autototem does.
 */
public final class AutoTotemB extends SimCheck {

    private static final class S {
        long lastSeq = Long.MIN_VALUE;
        final Deque<Double> times = new ArrayDeque<>();
    }

    private final Map<UUID, S> state = new ConcurrentHashMap<>();

    public AutoTotemB(KoalaGuard plugin) {
        super(plugin, "autototemb", CheckCategory.COMBAT, "Machine-consistent totem timing");
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
            while (s.times.size() > cfgI("sample-cap", 20)) s.times.removeFirst();
            newCycle = true;
        }
        if (!newCycle) return;

        if (s.times.size() < cfgI("min-samples", 5)) return;
        double mean = MathUtil.average(s.times);
        double sd = MathUtil.standardDeviation(s.times);
        if (sd < cfgD("max-sd-ms", 30.0) && mean < cfgD("max-mean-ms", 800.0)) {
            if (diverge(ctx, cfgD("score", 8.0), cfgD("threshold", 10.0),
                    cfgI("min-streak", 2),
                    String.format("consistent timing mean=%.0fms sd=%.1fms n=%d",
                            mean, sd, s.times.size()), false)) {
                armCombatCancel(ctx);
            }
        } else {
            clean(ctx, 1.5);
        }
    }

    @Override
    public void cleanup(UUID uuid) {
        super.cleanup(uuid);
        state.remove(uuid);
    }
}
