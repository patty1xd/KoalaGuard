package com.koalaguard.engine.checks.combat;

import com.koalaguard.KoalaGuard;
import com.koalaguard.check.CheckCategory;
import com.koalaguard.engine.check.CheckContext;
import com.koalaguard.engine.check.SimCheck;
import com.koalaguard.engine.packet.CapturedPacket;
import com.koalaguard.engine.packet.PacketKind;
import com.koalaguard.util.MathUtil;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Macro / KeyMacro / AutoMacro — automation of input PRESSES, not the result.
 *
 * A human releasing-and-tapping W to W-tap (sprint-cancel for max knockback)
 * or pressing space at the exact tick a vanilla cooldown ends has 8–15 ms of
 * intrinsic jitter every attempt. Macros lock to the server tick (50 ms)
 * with ±0–1 ms variance over 20+ consecutive attempts. The macro's effects
 * are vanilla-legal (it's just pressing keys faster), so PredictionCheck's
 * envelope clears them — they have to be caught by the TIMING fingerprint
 * itself.
 *
 * Two independent macros covered:
 *  S0  W-TAP / SPRINT-TOGGLE macro. Tracks the gap between consecutive
 *      START_SPRINTING ENTITY_ACTION packets. A human W-tapper hits ~12 ms
 *      mean with sd ≳ 6 ms; the macro hits 50 ms mean with sd &lt; 2 ms (snapped
 *      to server tick).
 *  S1  JUMP-RESET macro. Tracks the gap between consecutive jump impulses
 *      (positive Y-delta crossings while not on simGround). Same shape — a
 *      human's jump-reset has clear inter-attempt jitter; the macro is
 *      tick-locked.
 *
 * Both gated on combat: a player navigating with sprint+jump in the open
 * world has plenty of legitimate consistency over short stretches. Only
 * sustained machine consistency DURING active combat (recent attack within
 * 1.5 s) fires.
 */
public final class MacroCheck extends SimCheck {

    private static final class S {
        long lastSprintToggleNanos = Long.MIN_VALUE;
        long lastJumpImpulseNanos  = Long.MIN_VALUE;
        long lastSeenSprintSeq     = Long.MIN_VALUE;
        long lastJumpFrameTick     = Long.MIN_VALUE;
        final Deque<Long> sprintIntervalsMs = new ArrayDeque<>();
        final Deque<Long> jumpIntervalsMs   = new ArrayDeque<>();
    }

    private final Map<UUID, S> state = new ConcurrentHashMap<>();

    public MacroCheck(KoalaGuard plugin) {
        super(plugin, "macro", CheckCategory.COMBAT,
                "Tick-locked input macro (W-tap / jump-reset)");
    }

    @Override
    public void onTick(CheckContext ctx) {
        if (ctx.unstableBasic()) return;
        UUID id = ctx.data.getUuid();
        S s = state.computeIfAbsent(id, k -> new S());

        // Combat gate — only judge inside a real fight. A player just
        // running around with sprint+jump shouldn't be evaluated.
        long combatWindowNs = cfgL("combat-window-ns", 1_500_000_000L);
        long nowNs = System.nanoTime();
        boolean inCombat = ctx.state.combat.lastAttackNanos > Long.MIN_VALUE / 2
                && (nowNs - ctx.state.combat.lastAttackNanos) <= combatWindowNs;
        if (!inCombat) { clean(ctx, 0.5); return; }

        // ─── S0 — Sprint-toggle macro ───
        // ENTITY_ACTION packets with strA=START_SPRINTING. Track the gap
        // between successive starts (release + press cycle).
        List<CapturedPacket> recent = ctx.state.log.recent(160);
        long maxSprintSeq = s.lastSeenSprintSeq;
        // chronological order (oldest first)
        for (int i = recent.size() - 1; i >= 0; i--) {
            CapturedPacket p = recent.get(i);
            if (p.kind != PacketKind.ENTITY_ACTION) continue;
            if (!"START_SPRINTING".equals(p.strA)) continue;
            if (p.seq <= s.lastSeenSprintSeq) continue;
            if (p.seq > maxSprintSeq) maxSprintSeq = p.seq;
            if (s.lastSprintToggleNanos != Long.MIN_VALUE) {
                long ms = (p.recvNanos - s.lastSprintToggleNanos) / 1_000_000L;
                if (ms > 0 && ms < 2000) {
                    s.sprintIntervalsMs.addLast(ms);
                    while (s.sprintIntervalsMs.size() > cfgI("sample-cap", 24))
                        s.sprintIntervalsMs.removeFirst();
                }
            }
            s.lastSprintToggleNanos = p.recvNanos;
        }
        s.lastSeenSprintSeq = maxSprintSeq;

        // ─── S1 — Jump-reset macro ───
        // A jump impulse = a frame whose dy crosses from <=0 to a fresh
        // positive value (~0.42 for a standard jump). Look at the recent
        // frame history.
        for (var f : ctx.state.recentFrames(40)) {
            if (f.tick <= s.lastJumpFrameTick) break;
            if (f.dy <= cfgD("min-jump-impulse", 0.30)) continue;
            // crude impulse detection: dy is large positive. Approx nanos by
            // mapping tick to server-time isn't trivial; we use System.nanoTime()
            // - (current.tick - f.tick) * 50e6 as a rough cursor.
            long approxNs = nowNs - (ctx.state.tick - f.tick) * 50_000_000L;
            if (s.lastJumpImpulseNanos != Long.MIN_VALUE) {
                long ms = (approxNs - s.lastJumpImpulseNanos) / 1_000_000L;
                if (ms > 0 && ms < 3000) {
                    s.jumpIntervalsMs.addLast(ms);
                    while (s.jumpIntervalsMs.size() > cfgI("sample-cap", 24))
                        s.jumpIntervalsMs.removeFirst();
                }
            }
            s.lastJumpImpulseNanos = approxNs;
        }
        if (ctx.state.current != null) s.lastJumpFrameTick = ctx.state.current.tick;

        double bad = 0;
        StringBuilder why = new StringBuilder();

        // Raised sd<4ms to sd<1.5ms — skilled W-tappers locked to click cadence
        // can produce sd=2-3 ms over a short window; sub-2 ms variance over a
        // long sample is the actual macro signature. Sample floor raised to
        // 24 so a transient skilled burst can't confirm.
        int minSamples = cfgI("min-samples", 24);
        double maxSdMs = cfgD("max-sd-ms", 1.5);

        if (s.sprintIntervalsMs.size() >= minSamples) {
            double sd = MathUtil.standardDeviation(s.sprintIntervalsMs);
            double mean = MathUtil.average(s.sprintIntervalsMs);
            if (sd < maxSdMs && mean < cfgD("max-sprint-mean-ms", 250.0)) {
                bad += cfgD("sprint-score", 7.0);
                why.append(String.format("sprint-toggle macro mean=%.0fms sd=%.1fms n=%d ",
                        mean, sd, s.sprintIntervalsMs.size()));
            }
        }
        if (s.jumpIntervalsMs.size() >= minSamples) {
            double sd = MathUtil.standardDeviation(s.jumpIntervalsMs);
            double mean = MathUtil.average(s.jumpIntervalsMs);
            if (sd < maxSdMs && mean < cfgD("max-jump-mean-ms", 800.0)) {
                bad += cfgD("jump-score", 7.0);
                why.append(String.format("jump-reset macro mean=%.0fms sd=%.1fms n=%d ",
                        mean, sd, s.jumpIntervalsMs.size()));
            }
        }

        if (bad > 0) {
            diverge(ctx, bad, cfgD("threshold", 9.0), cfgI("min-streak", 3),
                    "macro :: " + why.toString().trim(), false);
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
