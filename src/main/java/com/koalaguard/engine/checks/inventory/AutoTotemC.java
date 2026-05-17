package com.koalaguard.engine.checks.inventory;

import com.koalaguard.KoalaGuard;
import com.koalaguard.check.CheckCategory;
import com.koalaguard.engine.check.CheckContext;
import com.koalaguard.engine.check.SimCheck;
import com.koalaguard.engine.state.TotemCycle;
import com.koalaguard.util.MathUtil;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * AutoTotem Type C (TotemGuard) — INDEPENDENT check.
 *
 * Trigger: impossibly consistent standard deviations over multiple windows.
 * Even an autototem with a little jitter still produces a near-constant SPREAD;
 * a human's spread itself varies. We slide a window over the cycle times,
 * record each window's std-dev, and flag when the std-dev OF those std-devs is
 * itself tiny over many windows.
 */
public final class AutoTotemC extends SimCheck {

    private static final class S {
        long lastSeq = Long.MIN_VALUE;
        final Deque<Double> times = new ArrayDeque<>();
        final Deque<Double> sds = new ArrayDeque<>();
    }

    private final Map<UUID, S> state = new ConcurrentHashMap<>();

    public AutoTotemC(KoalaGuard plugin) {
        super(plugin, "autototemc", CheckCategory.COMBAT, "Consistent totem-timing spread");
    }

    @Override
    public void onTick(CheckContext ctx) {
        if (ctx.unstableBasic()) return;
        UUID id = ctx.data.getUuid();
        S s = state.computeIfAbsent(id, k -> new S());

        int window = cfgI("window", 5);
        boolean newCycle = false;
        for (TotemCycle c : ctx.state.inv.cycles) {
            if (c.seq <= s.lastSeq) continue;
            s.lastSeq = c.seq;
            s.times.addLast(c.cycleMs);
            while (s.times.size() > cfgI("sample-cap", 40)) s.times.removeFirst();
            if (s.times.size() >= window) {
                List<Double> win = new ArrayList<>(s.times);
                win = win.subList(win.size() - window, win.size());
                s.sds.addLast(MathUtil.standardDeviation(win));
                while (s.sds.size() > cfgI("sd-cap", 20)) s.sds.removeFirst();
            }
            newCycle = true;
        }
        if (!newCycle) return;

        if (s.sds.size() < cfgI("min-windows", 4)) return;
        double sdOfSd = MathUtil.standardDeviation(s.sds);
        double meanSd = MathUtil.average(s.sds);
        if (sdOfSd < cfgD("max-sd-of-sd", 8.0) && meanSd < cfgD("max-mean-sd", 60.0)) {
            if (diverge(ctx, cfgD("score", 8.0), cfgD("threshold", 10.0),
                    cfgI("min-streak", 2),
                    String.format("uniform spread sd(sd)=%.2f meanSd=%.1f n=%d",
                            sdOfSd, meanSd, s.sds.size()), false)) {
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
