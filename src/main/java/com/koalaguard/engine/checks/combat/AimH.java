package com.koalaguard.engine.checks.combat;

import com.koalaguard.KoalaGuard;
import com.koalaguard.check.CheckCategory;
import com.koalaguard.engine.check.CheckContext;
import com.koalaguard.engine.check.SimCheck;
import com.koalaguard.engine.packet.CapturedPacket;
import com.koalaguard.engine.packet.PacketKind;
import com.koalaguard.engine.state.PositionFrame;
import com.koalaguard.engine.util.Combat;
import com.koalaguard.util.MathUtil;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Aim H — target-acquisition reaction time. INDEPENDENT aim check.
 *
 * Catches triggerbot / aura that auto-attacks the instant a target becomes
 * reachable (the player's own aim can look perfectly legal, so the mechanics
 * checks miss it). Using the lag-comp victim history, for the FIRST attack of
 * an engagement we find when that victim transitioned from out-of-reach to
 * in-reach (the acquisition moment) and measure how fast the attack followed.
 *
 * A human needs perception + decision + click (≳150 ms, and highly VARIABLE).
 * A bot fires almost immediately and with near-constant latency. Only flags
 * when many clean acquisitions are BOTH inhumanly fast AND inhumanly
 * consistent — a merely fast human is fast but variable, so it clears.
 *
 * FP guards: only the first hit of an engagement (not combo hits); only when
 * the target genuinely entered reach from outside (continued fights produce no
 * sample); large sample; low mean AND low sd; sustained streak.
 */
public final class AimH extends SimCheck {

    private static final class S {
        long lastSeq = Long.MIN_VALUE;
        int  engagedId = Integer.MIN_VALUE;
        long lastAtkNanos = Long.MIN_VALUE;
        final Deque<Double> react = new ArrayDeque<>();
    }

    private final Map<UUID, S> state = new ConcurrentHashMap<>();

    public AimH(KoalaGuard plugin) {
        super(plugin, "aimh", CheckCategory.COMBAT, "Inhuman target-acquisition reaction");
    }

    @Override
    public void onTick(CheckContext ctx) {
        if (ctx.unstableBasic()) return;
        UUID id = ctx.data.getUuid();
        S s = state.computeIfAbsent(id, k -> new S());

        long engageGapNs = cfgL("engage-gap-ns", 1_200_000_000L);
        double reach = cfgD("reach", 3.2);
        double maxPlausibleMs = cfgD("max-plausible-ms", 2000.0);

        List<CapturedPacket> recent = ctx.state.log.recent(160);
        List<CapturedPacket> chrono = new ArrayList<>(recent);
        java.util.Collections.reverse(chrono);

        long max = s.lastSeq;
        for (CapturedPacket p : chrono) {
            if (p.kind != PacketKind.INTERACT_ENTITY) continue;
            if (!String.valueOf(p.objA).contains("ATTACK")) continue;
            if (p.seq > max) max = p.seq;
            if (p.seq <= s.lastSeq) continue;

            int vid = p.intA;
            long tatk = p.recvNanos;
            boolean firstOfEngagement = vid != s.engagedId
                    || tatk - s.lastAtkNanos > engageGapNs;
            s.engagedId = vid;
            s.lastAtkNanos = tatk;
            if (!firstOfEngagement) continue;

            PositionFrame f = ctx.state.frameAtOrBefore(p.tickIndex);
            double[] el = Combat.eyeLook(f, ctx.player);
            double ex = el[0], ey = el[1], ez = el[2];

            // Find the most recent out-of-reach → in-reach transition for this
            // victim at or before the attack (a genuine acquisition).
            long acq = -1;
            boolean wasOut = false;
            for (double[] e : ctx.state.targets.history(vid)) {
                if (e[0] > tatk) break;
                double dx = Math.max(Math.max(e[1] - ex, 0), ex - e[4]);
                double dy = Math.max(Math.max(e[2] - ey, 0), ey - e[5]);
                double dz = Math.max(Math.max(e[3] - ez, 0), ez - e[6]);
                double d = Math.sqrt(dx * dx + dy * dy + dz * dz);
                if (d > reach) { wasOut = true; acq = -1; }
                else if (wasOut && acq < 0) acq = (long) e[0];
            }
            if (acq < 0) continue;                          // no clean acquisition

            double reactMs = (tatk - acq) / 1_000_000.0;
            if (reactMs < 0 || reactMs > maxPlausibleMs) continue;
            s.react.addLast(reactMs);
            while (s.react.size() > cfgI("sample-cap", 24)) s.react.removeFirst();
        }
        s.lastSeq = max;

        if (s.react.size() < cfgI("min-samples", 6)) return;
        double mean = MathUtil.average(s.react);
        double sd = MathUtil.standardDeviation(s.react);
        if (debug()) {
            plugin.getLogger().info("[aimh] " + ctx.player.getName()
                    + " reactMean=" + String.format("%.0f", mean)
                    + "ms sd=" + String.format("%.0f", sd)
                    + "ms n=" + s.react.size());
        }
        if (mean < cfgD("max-mean-ms", 90.0) && sd < cfgD("max-sd-ms", 45.0)) {
            // Alert only — staff correlate; no single-confirm combat cancel.
            diverge(ctx, cfgD("score", 7.0), cfgD("threshold", 10.0),
                    cfgI("min-streak", 3),
                    String.format("inhuman reaction mean=%.0fms sd=%.0fms n=%d",
                            mean, sd, s.react.size()), false);
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
