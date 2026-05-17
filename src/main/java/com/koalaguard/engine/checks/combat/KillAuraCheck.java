package com.koalaguard.engine.checks.combat;

import com.koalaguard.KoalaGuard;
import com.koalaguard.check.CheckCategory;
import com.koalaguard.engine.check.CheckContext;
import com.koalaguard.engine.check.SimCheck;
import com.koalaguard.engine.packet.CapturedPacket;
import com.koalaguard.engine.packet.PacketKind;
import com.koalaguard.engine.util.Combat;
import com.koalaguard.util.MathUtil;
import org.bukkit.Location;
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
 * KillAura / Aimbot. Aim is taken from the player's LATEST sent rotation +
 * live server eye (a stale reconstructed frame produced garbage for a
 * stationary attacker — that was why nothing detected).
 *
 *  S0  Attack outside the look cone — the angle between the sent look and the
 *      victim's hitbox is huge (no-rotate / backtrack / rage aura). You
 *      physically cannot melee something you are looking 70°+ away from.
 *  S1  Aim too perfect — over many hits the aim error has a tiny mean AND tiny
 *      std-dev (silent aimbot centres the box every time; humans have spread).
 *  S2  Multi-target — distinct living targets struck in a short window.
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

        Location eye = ctx.player.getEyeLocation();
        float yaw = ctx.state.prevYaw, pitch = ctx.state.prevPitch;

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
            if (p.seq <= s.lastSeq) continue;                           // new hits only

            Entity victim = Combat.resolveById(ctx.player, p.intA, 8.0);
            if (!(victim instanceof LivingEntity) || victim == ctx.player) continue;
            double err = Combat.aimAngle(eye.getX(), eye.getY(), eye.getZ(), yaw, pitch, victim);
            s.aimErr.addLast(err);
            while (s.aimErr.size() > cfgI("sample-cap", 30)) s.aimErr.removeFirst();

            if (err > cfgD("max-cone-deg", 70.0)) {                     // S0 immediate
                bad += cfgD("cone-score", 7.0);
                why.append(String.format("hit %.0f° off-aim ", err));
            }
        }
        s.lastSeq = maxSeen;

        if (s.aimErr.size() >= cfgI("min-samples", 12)) {               // S1
            double mean = MathUtil.average(s.aimErr);
            double sd = MathUtil.standardDeviation(s.aimErr);
            if (mean < cfgD("max-mean-deg", 2.0) && sd < cfgD("max-sd-deg", 1.2)) {
                bad += cfgD("aim-score", 8.0);
                why.append(String.format("machine aim mean=%.2f° sd=%.2f° n=%d ",
                        mean, sd, s.aimErr.size()));
            }
        }
        int maxT = cfgI("max-targets", 3);
        if (targets.size() >= maxT) {                                   // S2
            bad += (targets.size() - maxT + 1) * cfgD("multi-score", 5.0);
            why.append(targets.size()).append(" targets ");
        }

        if (debug() && !s.aimErr.isEmpty()) {
            plugin.getLogger().info("[killaura] " + ctx.player.getName()
                    + " lastErr=" + String.format("%.1f", s.aimErr.peekLast())
                    + "° n=" + s.aimErr.size() + " bad=" + bad);
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
