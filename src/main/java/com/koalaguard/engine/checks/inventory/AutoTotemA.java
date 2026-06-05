package com.koalaguard.engine.checks.inventory;

import com.koalaguard.KoalaGuard;
import com.koalaguard.check.CheckCategory;
import com.koalaguard.engine.check.CheckContext;
import com.koalaguard.engine.check.SimCheck;
import com.koalaguard.engine.state.TotemCycle;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * AutoTotem Type A (TotemGuard) — INDEPENDENT check.
 *
 * Trigger: an impossibly short delay between selecting a totem and placing it
 * into the off-hand slot. A human cannot open the inventory, find the totem and
 * drop it into the off hand in tens of milliseconds; an autototem does. Reads
 * the packet-precise totem cycle only; owns nothing else.
 */
public final class AutoTotemA extends SimCheck {

    private final Map<UUID, Long> lastSeq = new ConcurrentHashMap<>();
    private final Map<UUID, java.util.ArrayDeque<Double>> recentMs
            = new ConcurrentHashMap<>();

    public AutoTotemA(KoalaGuard plugin) {
        super(plugin, "autototema", CheckCategory.COMBAT, "Impossibly fast totem re-equip");
    }

    @Override
    public void onTick(CheckContext ctx) {
        if (ctx.unstableBasic()) return;
        UUID id = ctx.data.getUuid();
        long seen = lastSeq.getOrDefault(id, Long.MIN_VALUE);
        long max = seen;
        boolean flagged = false;

        for (TotemCycle c : ctx.state.inv.cycles) {
            if (c.seq <= seen) continue;
            if (c.seq > max) max = c.seq;
            // Single-packet F-swap exemption: a legit player holding a totem
            // in main hand pressing F after popping moves it to off-hand in
            // one SWAP_ITEM_WITH_OFFHAND packet, ~50-100 ms after the pop —
            // identical timing to an autototem but a normal human motion.
            // Skip the burstSize==1 case when the source was an F-swap (the
            // cycle's burstSize==1 + cycleMs in the human range is the
            // signature). Real autototem produces a multi-packet click burst
            // (inventory drag), which the burstSize>=2 branch still catches.
            if (c.burstSize <= 1) continue;
            double metric = c.selectToEquipMs;
            // Track recent re-equip ms for consistency analysis (Wurst-style
            // ±100ms randomisation still clusters near 100-200ms — much
            // tighter than human inventory navigation, which ranges 600ms+
            // with large jitter).
            java.util.ArrayDeque<Double> q =
                    recentMs.computeIfAbsent(id, k -> new java.util.ArrayDeque<>());
            q.addLast(metric);
            while (q.size() > cfgI("sample-cap", 8)) q.removeFirst();

            if (metric <= cfgD("max-ms", 90.0) || c.cycleMs <= cfgD("max-cycle-ms", 110.0)) {
                if (diverge(ctx, cfgD("score", 7.0), cfgD("threshold", 9.0),
                        cfgI("min-streak", 3),
                        String.format("re-equip %.0fms (cycle %.0fms, burst=%d)",
                                metric, c.cycleMs, c.burstSize), false)) {
                    armCombatCancel(ctx);
                }
                flagged = true;
            } else if (q.size() >= cfgI("consistency-min-samples", 5)) {
                // Inhuman-consistency path: all samples fall in a narrow band
                // (max-min < 60ms) AND mean is under the human floor (~250ms).
                double mn = Double.MAX_VALUE, mx = Double.MIN_VALUE, sum = 0;
                for (double v : q) { if (v < mn) mn = v; if (v > mx) mx = v; sum += v; }
                double mean = sum / q.size();
                if (mx - mn < cfgD("consistency-max-spread-ms", 60.0)
                        && mean < cfgD("consistency-max-mean-ms", 250.0)) {
                    if (diverge(ctx, cfgD("consistency-score", 5.0), cfgD("threshold", 9.0),
                            cfgI("consistency-min-streak", 2),
                            String.format("totem re-equip too-consistent: mean=%.0fms spread=%.0fms n=%d",
                                    mean, mx - mn, q.size()), false)) {
                        armCombatCancel(ctx);
                    }
                    flagged = true;
                }
            }
        }
        lastSeq.put(id, max);
        if (!flagged && max > seen) clean(ctx, 2.0);
    }

    @Override
    public void cleanup(UUID uuid) {
        super.cleanup(uuid);
        lastSeq.remove(uuid);
    }
}
