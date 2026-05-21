package com.koalaguard.engine.checks.combat;

import com.koalaguard.KoalaGuard;
import com.koalaguard.check.CheckCategory;
import com.koalaguard.engine.check.CheckContext;
import com.koalaguard.engine.check.SimCheck;
import com.koalaguard.engine.packet.CapturedPacket;
import com.koalaguard.engine.packet.PacketKind;
import com.koalaguard.util.MathUtil;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * AutoClicker — NOT a CPS threshold. It measures the REGULARITY of attack
 * intervals: a human (even a fast butterfly/jitter clicker) has a large,
 * irregular spread; an autoclicker's intervals collapse to a near-constant
 * value (tiny coefficient of variation), often with exact duplicates. Only a
 * sustained, fast, low-variation stream over many samples confirms.
 */
public final class AutoClickerCheck extends SimCheck {

    private static final class S {
        long lastSeq = Long.MIN_VALUE;
        long lastNanos = -1;
        final Deque<Long> intervals = new ArrayDeque<>();   // ms between attacks
    }

    private final Map<UUID, S> state = new ConcurrentHashMap<>();

    public AutoClickerCheck(KoalaGuard plugin) {
        super(plugin, "autoclicker", CheckCategory.COMBAT, "Machine-regular click cadence");
    }

    @Override
    public void onTick(CheckContext ctx) {
        if (ctx.unstableBasic()) return;
        UUID id = ctx.data.getUuid();
        S s = state.computeIfAbsent(id, k -> new S());

        List<CapturedPacket> recent = ctx.state.log.recent(96);
        List<CapturedPacket> chrono = new ArrayList<>(recent);
        java.util.Collections.reverse(chrono);

        long max = s.lastSeq;
        for (CapturedPacket p : chrono) {
            if (p.kind != PacketKind.INTERACT_ENTITY) continue;
            if (!String.valueOf(p.objA).contains("ATTACK")) continue;
            if (p.seq <= s.lastSeq) continue;
            if (p.seq > max) max = p.seq;
            if (s.lastNanos > 0) {
                long ms = (p.recvNanos - s.lastNanos) / 1_000_000L;
                if (ms > 0 && ms < 2000) {
                    s.intervals.addLast(ms);
                    while (s.intervals.size() > cfgI("sample-cap", 40))
                        s.intervals.removeFirst();
                }
            }
            s.lastNanos = p.recvNanos;
        }
        s.lastSeq = max;

        if (s.intervals.size() < cfgI("min-samples", 18)) { clean(ctx, 0.3); return; }

        double mean = MathUtil.average(s.intervals);
        double sd = MathUtil.standardDeviation(s.intervals);
        double cv = mean <= 0 ? 1.0 : sd / mean;
        int dupes = MathUtil.duplicates(s.intervals);

        // ── Three independent inhuman signatures (any one over a large
        //    sample is enough). Covers every modern variant:
        //      • constant interval (CV → 0)
        //      • randomized ±jitter that doesn't break statistical signal
        //        (CV stays well below human spread)
        //      • duplicate-heavy patterns (Wurst's "speed randomization"
        //        snaps to whole-millisecond steps; produces many exact dupes)
        //      • butterfly emulation / bimodal (low CV per cluster ⇒ overall
        //        CV stays moderate but mean clearly inhuman)
        //      • triggerbot: same statistical fingerprint — its click cadence
        //        IS the autoclicker's, just bound to a target-in-crosshair gate.
        boolean fast = mean < cfgD("max-mean-ms", 250.0);
        boolean robotic = cv < cfgD("max-cv", 0.07)
                || dupes >= cfgI("max-duplicates", 14);

        // High-rate floor: sustained > 14 CPS over many samples is the bot
        // signature that defeats even ±jitter randomization (no human keeps
        // ≥14 CPS over 30+ clicks).
        boolean inhumanRate = mean < cfgD("inhuman-mean-ms", 72.0)
                && s.intervals.size() >= cfgI("inhuman-min-samples", 28);

        if ((fast && robotic) || inhumanRate) {
            diverge(ctx, cfgD("score", 6.0), cfgD("threshold", 10.0),
                    cfgI("min-streak", 3),
                    String.format("clicks mean=%.0fms cv=%.3f dup=%d n=%d%s",
                            mean, cv, dupes, s.intervals.size(),
                            inhumanRate ? " (inhuman rate)" : ""), false);
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
