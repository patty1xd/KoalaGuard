package com.koalaguard.engine.checks.combat;

import com.koalaguard.KoalaGuard;
import com.koalaguard.check.CheckCategory;
import com.koalaguard.engine.check.CheckContext;
import com.koalaguard.engine.check.SimCheck;
import com.koalaguard.engine.packet.CapturedPacket;
import com.koalaguard.engine.packet.PacketKind;
import com.koalaguard.engine.sim.CollisionEngine;
import com.koalaguard.engine.state.PositionFrame;
import com.koalaguard.engine.util.Combat;
import com.koalaguard.util.MathUtil;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.util.BoundingBox;

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
 * KillAura / Aimbot — REBUILT to be time-aligned.
 *
 * The old version measured aim from the LIVE server eye + the LATEST sent
 * rotation against the victim's LIVE hitbox, evaluated a whole tick after the
 * hit. Nothing lined up: a precise silent aura passed the loose 70° cone and
 * the statistical path drowned in the timing noise — which is exactly why it
 * never detected. Every signal here is reconstructed at the EXACT tick the
 * attack packet was bound to (the same {@code frameAtOrBefore} reconstruction
 * that makes Reach/Criticals reliable), so tight, FP-safe thresholds work.
 *
 *  S0  Through-wall hit — the reconstructed eye→victim segment is obstructed by
 *      a solid block. You cannot melee an entity through a wall.
 *  S1  Snap-aim — a large single-tick rotation lands the look precisely on the
 *      target on the attack tick while the player is otherwise barely turning
 *      (the rotation is DRIVEN by the attack, not by the player).
 *  S2  Machine aim — over a large sample of hits the time-aligned aim error has
 *      a tiny mean AND tiny std-dev (a human has spread; an aimbot centres it).
 *  S3  Multi-target — distinct living targets struck within a short window.
 */
public final class KillAuraCheck extends SimCheck {

    private static final class S {
        long lastSeq = Long.MIN_VALUE;
        final Deque<Double> aimErr = new ArrayDeque<>();
    }

    private final Map<UUID, S> state = new ConcurrentHashMap<>();

    public KillAuraCheck(KoalaGuard plugin) {
        super(plugin, "killaura", CheckCategory.COMBAT, "Aimbot / through-wall / multi-target aura");
    }

    @Override
    public void onTick(CheckContext ctx) {
        if (ctx.unstableBasic()) return;
        UUID id = ctx.data.getUuid();
        S s = state.computeIfAbsent(id, k -> new S());

        List<CapturedPacket> recent = ctx.state.log.recent(192);
        List<CapturedPacket> chrono = new ArrayList<>(recent);
        java.util.Collections.reverse(chrono);

        // Per-tick rotation delta map for the snap signal (tick -> |dYaw|).
        List<PositionFrame> frames = ctx.state.recentFrames(96);
        double turnSum = 0; int turnN = 0;
        for (PositionFrame f : frames) { turnSum += f.dYaw; turnN++; }
        double avgTurn = turnN > 0 ? turnSum / turnN : 0;

        long windowNs = cfgL("multi-window-ns", 800_000_000L);
        long newest = -1;
        for (CapturedPacket p : recent) {
            if (p.kind == PacketKind.INTERACT_ENTITY
                    && String.valueOf(p.objA).contains("ATTACK")) { newest = p.recvNanos; break; }
        }

        long maxSeen = s.lastSeq;
        Set<Integer> targets = new HashSet<>();
        double bad = 0;
        StringBuilder why = new StringBuilder();

        double snapMin   = cfgD("snap-min-deg", 28.0);
        double lockCone  = cfgD("snap-lock-cone-deg", 9.0);
        double calmTurn  = cfgD("snap-calm-deg", 4.0);

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

            // Reconstruct the eye/look at the EXACT attack tick.
            PositionFrame f = ctx.state.frameAtOrBefore(p.tickIndex);
            double[] el = Combat.eyeLook(f, ctx.player);
            double ex = el[0], ey = el[1], ez = el[2];
            float yaw = (float) el[3], pitch = (float) el[4];

            double err = Combat.aimAngle(ex, ey, ez, yaw, pitch, victim);
            s.aimErr.addLast(err);
            while (s.aimErr.size() > cfgI("sample-cap", 30)) s.aimErr.removeFirst();

            // S0 — through-wall hit.
            BoundingBox b = victim.getBoundingBox();
            if (CollisionEngine.rayBlocked(ctx.player.getWorld(), ex, ey, ez,
                    b.getCenterX(), b.getCenterY(), b.getCenterZ())) {
                bad += cfgD("wall-score", 8.0);
                why.append("through-wall hit ");
            }

            // S1 — snap onto target: a big single-tick turn near the attack
            // tick that lands the aim inside a tight cone, while the player is
            // otherwise barely turning.
            double snap = 0;
            for (PositionFrame g : frames) {
                if (g.tick <= p.tickIndex && g.tick >= p.tickIndex - 2) {
                    snap = Math.max(snap, g.dYaw);
                }
            }
            if (snap >= snapMin && err <= lockCone && avgTurn <= calmTurn) {
                bad += cfgD("snap-score", 7.0);
                why.append(String.format("snap %.0f°->%.1f° ", snap, err));
            }
        }
        s.lastSeq = maxSeen;

        // S2 — machine aim over a large time-aligned sample.
        if (s.aimErr.size() >= cfgI("min-samples", 14)) {
            double mean = MathUtil.average(s.aimErr);
            double sd = MathUtil.standardDeviation(s.aimErr);
            if (mean < cfgD("max-mean-deg", 3.0) && sd < cfgD("max-sd-deg", 1.4)) {
                bad += cfgD("aim-score", 8.0);
                why.append(String.format("machine aim mean=%.2f° sd=%.2f° n=%d ",
                        mean, sd, s.aimErr.size()));
            }
        }

        // S3 — multi-target.
        int maxT = cfgI("max-targets", 3);
        if (targets.size() >= maxT) {
            bad += (targets.size() - maxT + 1) * cfgD("multi-score", 5.0);
            why.append(targets.size()).append(" targets ");
        }

        if (debug() && !s.aimErr.isEmpty()) {
            plugin.getLogger().info("[killaura] " + ctx.player.getName()
                    + " lastErr=" + String.format("%.1f", s.aimErr.peekLast())
                    + "° n=" + s.aimErr.size() + " avgTurn="
                    + String.format("%.2f", avgTurn) + " bad=" + bad);
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
