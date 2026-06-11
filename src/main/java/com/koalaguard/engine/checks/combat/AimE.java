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
 * Aim E — machine-perfect aim lock (LAG-COMPENSATED). INDEPENDENT aim check.
 *
 * This is the GEOMETRIC catch for Meteor-style silent aim. Meteor spoofs the
 * rotation it sends the server straight onto the victim, so the aim error to
 * the hitbox is mathematically ~0 with ~0 variance every hit. A human always
 * has spread. The reason this could not be used before was victim desync — it
 * is now fixed: the victim hitbox is rewound to the attack packet's nanosecond
 * via {@link com.koalaguard.engine.state.TargetTracker}, and the attacker eye
 * is reconstructed at the attack tick, so the error is the TRUE at-hit error.
 *
 * Still conservative: large sample, knockback ticks excluded, only counts hits
 * with a real rewound hitbox, sustained streak — a human's natural spread
 * (sd ≳ 2-4°) clears it comfortably.
 */
public final class AimE extends SimCheck {

    private static final class S {
        long lastSeq = Long.MIN_VALUE;
        final Deque<Double> err = new ArrayDeque<>();
    }

    private final Map<UUID, S> state = new ConcurrentHashMap<>();

    public AimE(KoalaGuard plugin) {
        super(plugin, "aime", CheckCategory.COMBAT, "Machine-perfect aim lock");
    }

    @Override
    public void onTick(CheckContext ctx) {
        if (ctx.unstableBasic()) return;
        // The old gate skipped while the attacker had been damaged (<800 ms)
        // or knocked back (<1000 ms). In a real fight BOTH are continuously
        // true — being comboed refreshes them every hit — so the check that
        // exists to catch silent aim was effectively BLIND for entire
        // engagements ("aim cheats never get detected"). Neither event
        // corrupts the measurement: the aim error is computed from the
        // attacker's SENT rotation (packet-stamped) against the rewound
        // victim box; taking a hit moves the attacker's body, not the
        // truthfulness of their crosshair. Keep only a short velocity grace
        // for the single tick where a fresh knockback can shear the eye
        // position reconstruction.
        long now = System.currentTimeMillis();
        if (now - ctx.data.lastVelocityMs < cfgL("velocity-grace-ms", 300L)) return;

        UUID id = ctx.data.getUuid();
        S s = state.computeIfAbsent(id, k -> new S());

        List<CapturedPacket> recent = ctx.state.log.recent(160);
        List<CapturedPacket> chrono = new ArrayList<>(recent);
        java.util.Collections.reverse(chrono);

        long max = s.lastSeq;
        boolean any = false;
        for (CapturedPacket p : chrono) {
            if (p.kind != PacketKind.INTERACT_ENTITY) continue;
            if (!String.valueOf(p.objA).contains("ATTACK")) continue;
            if (p.seq > max) max = p.seq;
            if (p.seq <= s.lastSeq) continue;

            // Victim rewound to the attack instant — null ⇒ skip (never sample
            // a stale live position into the statistics).
            double[] box = ctx.state.targets.boxAt(p.intA, p.recvNanos);
            if (box == null) continue;

            PositionFrame f = ctx.state.frameAtOrBefore(p.tickIndex);
            double[] el = Combat.eyeLook(f, p, ctx.player);
            double err = Combat.aimAngle(el[0], el[1], el[2],
                    (float) el[3], (float) el[4], box);
            s.err.addLast(err);
            while (s.err.size() > cfgI("sample-cap", 40)) s.err.removeFirst();
            any = true;
        }
        s.lastSeq = max;

        if (s.err.size() < cfgI("min-samples", 20)) return;
        double mean = MathUtil.average(s.err);
        double sd = MathUtil.standardDeviation(s.err);
        // Lag-1 autocorrelation on the error series — humanized noise from
        // aimbots is generated per-tick and is essentially white (|r1| << 0.2),
        // while a human's natural aim drift is correlated tick-to-tick
        // (|r1| typically 0.3–0.7). Combined with low mean+sd, low |r1| is the
        // signature of synthetic jitter on top of a perfect lock.
        double r1 = 0.0;
        if (s.err.size() >= 8) {
            Double[] arr = s.err.toArray(new Double[0]);
            double num = 0, den = 0;
            for (int i = 0; i < arr.length; i++) {
                double d = arr[i] - mean;
                den += d * d;
                if (i > 0) num += (arr[i - 1] - mean) * d;
            }
            if (den > 1e-9) r1 = num / den;
        }
        boolean perfect = mean < cfgD("max-mean-deg", 2.5)
                && sd < cfgD("max-sd-deg", 1.3);
        boolean syntheticJitter = mean < cfgD("jitter-mean-deg", 3.5)
                && sd > cfgD("jitter-min-sd-deg", 0.4)
                && Math.abs(r1) < cfgD("max-acorr", 0.2);
        if (perfect || syntheticJitter) {
            // Alert only — never cancel combat off a single statistical confirm.
            diverge(ctx, cfgD("score", 7.0), cfgD("threshold", 10.0),
                    cfgI("min-streak", 3),
                    String.format("lag-comp machine lock mean=%.2f° sd=%.2f° r1=%.2f n=%d",
                            mean, sd, r1, s.err.size()), false);
        } else if (any) {
            clean(ctx, 1.0);
        }
    }

    @Override
    public void cleanup(UUID uuid) {
        super.cleanup(uuid);
        state.remove(uuid);
    }
}
