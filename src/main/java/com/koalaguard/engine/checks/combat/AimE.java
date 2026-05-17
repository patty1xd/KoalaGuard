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
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Aim E — machine lock (perfect-aim statistics). INDEPENDENT aim check.
 *
 * Over a LARGE sample of hits the time-aligned aim error has a tiny mean AND a
 * tiny std-dev: the crosshair is mathematically centred on the hitbox every
 * single time. Humans always have spread.
 *
 * This is the only aim signal that is sensitive to victim-position desync, so
 * it is deliberately the most conservative one — large sample, only sampled
 * when NOT taking knockback, and DISABLED by default. Enable it on servers
 * that want the extra coverage and can tolerate tuning.
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
        // Knockback moves the victim/attacker unpredictably — never sample then.
        if (System.currentTimeMillis() - ctx.data.lastVelocityMs < 1000
                || System.currentTimeMillis() - ctx.data.lastDamageMs < 800) return;

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

            Entity victim = Combat.resolveById(ctx.player, p.intA, 8.0);
            if (!(victim instanceof LivingEntity) || victim == ctx.player) continue;

            PositionFrame f = ctx.state.frameAtOrBefore(p.tickIndex);
            double[] el = Combat.eyeLook(f, ctx.player);
            double err = Combat.aimAngle(el[0], el[1], el[2],
                    (float) el[3], (float) el[4], victim);
            s.err.addLast(err);
            while (s.err.size() > cfgI("sample-cap", 40)) s.err.removeFirst();
            any = true;
        }
        s.lastSeq = max;

        if (s.err.size() < cfgI("min-samples", 25)) return;
        double mean = MathUtil.average(s.err);
        double sd = MathUtil.standardDeviation(s.err);
        if (mean < cfgD("max-mean-deg", 2.0) && sd < cfgD("max-sd-deg", 1.0)) {
            if (diverge(ctx, cfgD("score", 7.0), cfgD("threshold", 10.0),
                    cfgI("min-streak", 2),
                    String.format("machine lock mean=%.2f° sd=%.2f° n=%d",
                            mean, sd, s.err.size()), false)) {
                armCombatCancel(ctx);
            }
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
