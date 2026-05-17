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
 * AutoTotem Type D (TotemGuard) — INDEPENDENT check.
 *
 * Trigger: identical packet TIMING sequences in the re-equip burst. An
 * autototem fires the same few inventory packets with the same delays between
 * them every cycle. We bucket recent cycles by burst shape and flag when the
 * per-position gap std-dev across cycles is ~0 (the sequence is a recording).
 */
public final class AutoTotemD extends SimCheck {

    private static final class S {
        long lastSeq = Long.MIN_VALUE;
        final Deque<double[]> bursts = new ArrayDeque<>();   // gap arrays
    }

    private final Map<UUID, S> state = new ConcurrentHashMap<>();

    public AutoTotemD(KoalaGuard plugin) {
        super(plugin, "autototemd", CheckCategory.COMBAT, "Replayed totem packet sequence");
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
            if (c.gapsMs.length >= 1) {
                s.bursts.addLast(c.gapsMs);
                while (s.bursts.size() > cfgI("sample-cap", 16)) s.bursts.removeFirst();
            }
            newCycle = true;
        }
        if (!newCycle) return;

        // Group the recent bursts by their length (only same-shape sequences
        // are comparable) and find the largest group.
        Map<Integer, List<double[]>> byLen = new java.util.HashMap<>();
        for (double[] g : s.bursts) byLen.computeIfAbsent(g.length, k -> new ArrayList<>()).add(g);

        for (Map.Entry<Integer, List<double[]>> e : byLen.entrySet()) {
            List<double[]> grp = e.getValue();
            if (grp.size() < cfgI("min-samples", 4)) continue;
            int len = e.getKey();
            double worst = 0;
            for (int i = 0; i < len; i++) {
                List<Double> col = new ArrayList<>(grp.size());
                for (double[] g : grp) col.add(g[i]);
                worst = Math.max(worst, MathUtil.standardDeviation(col));
            }
            if (worst < cfgD("max-gap-sd-ms", 4.0)) {
                if (diverge(ctx, cfgD("score", 9.0), cfgD("threshold", 9.0),
                        cfgI("min-streak", 2),
                        String.format("replayed packet timing: %d cycles, %d-gap seq, maxSd=%.2fms",
                                grp.size(), len, worst), false)) {
                    armCombatCancel(ctx);
                }
                return;
            }
        }
        clean(ctx, 1.0);
    }

    @Override
    public void cleanup(UUID uuid) {
        super.cleanup(uuid);
        state.remove(uuid);
    }
}
