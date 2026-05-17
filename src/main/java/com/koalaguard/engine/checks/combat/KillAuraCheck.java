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
 * KillAura / Aimbot — REBUILT so it works WHILE STANDING STILL.
 *
 * The previous versions read snap/aim from {@code recentFrames()} (position
 * frames). A player testing an aura sends ROTATION-only packets — those update
 * the rotation history but create NO position frame — so that array was empty
 * and the check evaluated nothing. THAT is why it never detected. Everything
 * here is driven by the rotation history ({@code yawDeltas}/{@code pitchDeltas},
 * updated on every rotation packet) and the captured attack packets, none of
 * which need the player to be moving.
 *
 *  S0  No-aim hit — at the attack, the SENT look is outside the victim's
 *      hitbox cone (you cannot melee what your crosshair is not on). Catches
 *      silent / no-rotate / backtrack aura.
 *  S1  Through-wall hit — the eye→victim segment is obstructed by solid blocks.
 *  S2  Snap aim — a large single rotation step while otherwise barely turning,
 *      during combat (the rotation is driven by the attack, not the player).
 *  S3  Machine lock — over many hits the aim error mean AND std-dev are tiny.
 *  S4  Multi-target — distinct living targets struck within a short window.
 */
public final class KillAuraCheck extends SimCheck {

    private static final class S {
        long lastSeq = Long.MIN_VALUE;
        final Deque<Double> aimErr = new ArrayDeque<>();
    }

    private final Map<UUID, S> state = new ConcurrentHashMap<>();

    public KillAuraCheck(KoalaGuard plugin) {
        super(plugin, "killaura", CheckCategory.COMBAT, "Aimbot / no-aim / through-wall aura");
    }

    @Override
    public void onTick(CheckContext ctx) {
        if (ctx.unstableBasic()) return;
        UUID id = ctx.data.getUuid();
        S s = state.computeIfAbsent(id, k -> new S());

        List<CapturedPacket> recent = ctx.state.log.recent(192);
        List<CapturedPacket> chrono = new ArrayList<>(recent);
        java.util.Collections.reverse(chrono);

        // Rotation history — populated by EVERY rotation packet, even when the
        // player never moves. This is the fix.
        List<Float> yd = ctx.state.yawDeltas(cfgI("rot-samples", 40));
        List<Float> pd = ctx.state.pitchDeltas(cfgI("rot-samples", 40));

        long windowNs = cfgL("multi-window-ns", 800_000_000L);
        long newest = -1;
        for (CapturedPacket p : recent) {
            if (p.kind == PacketKind.INTERACT_ENTITY
                    && String.valueOf(p.objA).contains("ATTACK")) { newest = p.recvNanos; break; }
        }
        boolean inCombat = newest >= 0
                && (System.nanoTime() - newest) <= cfgL("combat-window-ns", 1_500_000_000L);

        long maxSeen = s.lastSeq;
        Set<Integer> targets = new HashSet<>();
        double bad = 0;
        StringBuilder why = new StringBuilder();

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

            // Eye/look at the attack: reconstructed frame if one exists, else
            // the authoritative live eye/look (correct for a still attacker).
            PositionFrame f = ctx.state.frameAtOrBefore(p.tickIndex);
            double[] el = Combat.eyeLook(f, ctx.player);
            double ex = el[0], ey = el[1], ez = el[2];
            float yaw = (float) el[3], pitch = (float) el[4];

            BoundingBox b = victim.getBoundingBox();
            double err = Combat.aimAngle(ex, ey, ez, yaw, pitch, victim);
            double dist = Math.max(0.5, Combat.distanceToBox(ex, ey, ez, victim));
            double radius = Math.max(b.getWidthX(), b.getHeight()) / 2.0 + 0.10;
            double cone = Math.toDegrees(Math.atan2(radius, dist))
                    + cfgD("aim-slack-deg", 12.0);

            s.aimErr.addLast(err);
            while (s.aimErr.size() > cfgI("sample-cap", 30)) s.aimErr.removeFirst();

            if (err > cone) {                                       // S0 no-aim
                bad += Math.min(cfgD("noaim-max", 9.0),
                        (err - cone) * cfgD("noaim-scale", 0.25));
                why.append(String.format("hit %.0f° off (cone %.0f°) ", err, cone));
            }

            if (CollisionEngine.rayBlocked(ctx.player.getWorld(), ex, ey, ez,
                    b.getCenterX(), b.getCenterY(), b.getCenterZ())) {     // S1
                bad += cfgD("wall-score", 8.0);
                why.append("through-wall ");
            }
        }
        s.lastSeq = maxSeen;

        // S2 — snap: a big single rotation step while the rest is calm.
        if (inCombat && !yd.isEmpty()) {
            double mx = 0, sum = 0;
            for (float v : yd) { mx = Math.max(mx, v); sum += v; }
            for (float v : pd) mx = Math.max(mx, v);
            double avg = sum / yd.size();
            if (mx >= cfgD("snap-min-deg", 30.0) && avg <= cfgD("snap-calm-deg", 4.0)) {
                bad += cfgD("snap-score", 6.0);
                why.append(String.format("snap %.0f° (avg %.1f°) ", mx, avg));
            }
        }

        // S3 — machine lock over a large sample.
        if (s.aimErr.size() >= cfgI("min-samples", 14)) {
            double mean = MathUtil.average(s.aimErr);
            double sd = MathUtil.standardDeviation(s.aimErr);
            if (mean < cfgD("max-mean-deg", 3.0) && sd < cfgD("max-sd-deg", 1.4)) {
                bad += cfgD("aim-score", 7.0);
                why.append(String.format("machine lock mean=%.2f° sd=%.2f° n=%d ",
                        mean, sd, s.aimErr.size()));
            }
        }

        // S4 — multi-target.
        int maxT = cfgI("max-targets", 3);
        if (targets.size() >= maxT) {
            bad += (targets.size() - maxT + 1) * cfgD("multi-score", 5.0);
            why.append(targets.size()).append(" targets ");
        }

        if (debug() && (!s.aimErr.isEmpty() || !yd.isEmpty())) {
            plugin.getLogger().info("[killaura] " + ctx.player.getName()
                    + " lastErr=" + (s.aimErr.isEmpty() ? "-"
                        : String.format("%.1f", s.aimErr.peekLast()))
                    + "° n=" + s.aimErr.size() + " inCombat=" + inCombat
                    + " bad=" + String.format("%.2f", bad));
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
