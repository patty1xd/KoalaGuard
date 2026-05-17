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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * KillAura / Aimbot.
 *
 *  S0  Aim-error consistency — for every hit, the angle between the SENT look
 *      and the victim's hitbox is recorded. A human's per-hit aim error has a
 *      real spread (targets move, you never perfectly centre every swing); an
 *      aimbot centres the box with sub-degree error EVERY time. Over many hits
 *      a tiny mean AND tiny std-dev is physically impossible for a human.
 *  S1  Multi-target — distinct living targets struck within a short window
 *      (vanilla sweep is one packet; rotating between targets is aura).
 *
 * Needs many samples + persistence, so legit PvP cannot trip it.
 */
public final class KillAuraCheck extends SimCheck {

    private static final class S {
        long lastSeq = Long.MIN_VALUE;
        final Deque<Double> aimErr = new ArrayDeque<>();
    }

    private final Map<UUID, S> state = new ConcurrentHashMap<>();

    public KillAuraCheck(KoalaGuard plugin) {
        super(plugin, "killaura", CheckCategory.COMBAT, "Aimbot / multi-target aura");
    }

    @Override
    public void onTick(CheckContext ctx) {
        if (ctx.unstableBasic()) return;
        UUID id = ctx.data.getUuid();
        S s = state.computeIfAbsent(id, k -> new S());

        List<CapturedPacket> recent = ctx.state.log.recent(160);
        List<CapturedPacket> chrono = new ArrayList<>(recent);
        java.util.Collections.reverse(chrono);

        long windowNs = cfgL("multi-window-ns", 800_000_000L);
        long newest = -1;
        for (CapturedPacket p : recent) {
            if (p.kind == PacketKind.INTERACT_ENTITY
                    && String.valueOf(p.objA).contains("ATTACK")) { newest = p.recvNanos; break; }
        }

        long maxSeen = s.lastSeq;
        Set<Integer> targets = new HashSet<>();
        for (CapturedPacket p : chrono) {
            if (p.kind != PacketKind.INTERACT_ENTITY) continue;
            if (!String.valueOf(p.objA).contains("ATTACK")) continue;
            if (newest >= 0 && newest - p.recvNanos <= windowNs) {
                Entity e0 = Combat.resolveById(ctx.player, p.intA, 8.0);
                if (e0 instanceof LivingEntity && e0 != ctx.player) targets.add(p.intA);
            }
            if (p.seq > maxSeen) maxSeen = p.seq;
            if (p.seq <= s.lastSeq) continue;                       // new hits only

            Entity victim = Combat.resolveById(ctx.player, p.intA, 8.0);
            if (!(victim instanceof LivingEntity) || victim == ctx.player) continue;
            PositionFrame f = ctx.state.frameAtOrBefore(p.tickIndex);
            double[] e = Combat.eyeLook(f, ctx.player);
            double err = Combat.aimAngle(e[0], e[1], e[2], (float) e[3], (float) e[4], victim);
            s.aimErr.addLast(err);
            while (s.aimErr.size() > cfgI("sample-cap", 30)) s.aimErr.removeFirst();
        }
        s.lastSeq = maxSeen;

        double bad = 0;
        StringBuilder why = new StringBuilder();

        if (s.aimErr.size() >= cfgI("min-samples", 14)) {
            double mean = MathUtil.average(s.aimErr);
            double sd = MathUtil.standardDeviation(s.aimErr);
            if (mean < cfgD("max-mean-deg", 2.0) && sd < cfgD("max-sd-deg", 1.2)) {
                bad += cfgD("aim-score", 8.0);
                why.append(String.format("machine aim mean=%.2f° sd=%.2f° n=%d ",
                        mean, sd, s.aimErr.size()));
            }
        }
        int maxT = cfgI("max-targets", 3);
        if (targets.size() >= maxT) {
            bad += (targets.size() - maxT + 1) * cfgD("multi-score", 5.0);
            why.append(targets.size()).append(" targets ");
        }

        if (bad > 0) {
            if (diverge(ctx, bad, cfgD("threshold", 10.0), cfgI("min-streak", 3),
                    "killaura :: " + why.toString().trim(), false)) {
                armCombatCancel(ctx);
            }
        } else {
            clean(ctx, 0.5);
        }
    }

    @Override
    public void cleanup(UUID uuid) {
        super.cleanup(uuid);
        state.remove(uuid);
    }
}
