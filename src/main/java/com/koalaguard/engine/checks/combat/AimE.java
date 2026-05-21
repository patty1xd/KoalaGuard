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
        long now = System.currentTimeMillis();
        if (now - ctx.data.lastVelocityMs < 1000 || now - ctx.data.lastDamageMs < 800) return;

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
        if (mean < cfgD("max-mean-deg", 2.5) && sd < cfgD("max-sd-deg", 1.3)) {
            // Alert only — never cancel combat off a single statistical confirm.
            diverge(ctx, cfgD("score", 7.0), cfgD("threshold", 10.0),
                    cfgI("min-streak", 3),
                    String.format("lag-comp machine lock mean=%.2f° sd=%.2f° n=%d",
                            mean, sd, s.err.size()), false);
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
