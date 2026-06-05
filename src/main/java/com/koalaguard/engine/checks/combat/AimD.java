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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Aim D — snap-and-return (silent / backtrack aura). INDEPENDENT aim check.
 *
 * The exact signature an aimbot produces and a human does not: the look snaps
 * a large amount onto the victim ON the attack tick, then snaps BACK to within
 * a few degrees of where it was pointing BEFORE the snap, within a couple of
 * ticks. A human who flicks to a target keeps fighting it (stays aimed) — they
 * do not instantly return their crosshair to looking away after every hit.
 * Three conditions must all hold (snap magnitude + on-target at hit + return),
 * which is why it does not false-positive on ordinary flick aiming.
 *
 * Uses tick-indexed frames, so it only runs while the player is moving (a real
 * fight); the standing-still aura case is covered by AimA.
 */
public final class AimD extends SimCheck {

    private final Map<UUID, Long> lastSeq = new ConcurrentHashMap<>();

    public AimD(KoalaGuard plugin) {
        super(plugin, "aimd", CheckCategory.COMBAT, "Snap-and-return aura signature");
    }

    @Override
    public void onTick(CheckContext ctx) {
        if (ctx.unstableBasic()) return;
        if (ctx.state.current == null) return;
        UUID id = ctx.data.getUuid();
        long seen = lastSeq.getOrDefault(id, Long.MIN_VALUE);
        long max = seen;

        int returnWin = cfgI("return-window-ticks", 4);
        double snapMin = cfgD("snap-min-deg", 22.0);
        double lockCone = cfgD("lock-cone-deg", 7.0);
        double returnEps = cfgD("return-eps-deg", 9.0);

        // tick -> frame, for the recent window.
        Map<Long, PositionFrame> byTick = new HashMap<>();
        for (PositionFrame g : ctx.state.recentFrames(96)) byTick.put(g.tick, g);
        long newestFrame = ctx.state.current.tick;

        List<CapturedPacket> recent = ctx.state.log.recent(160);
        List<CapturedPacket> chrono = new ArrayList<>(recent);
        java.util.Collections.reverse(chrono);

        boolean flagged = false, any = false;

        for (CapturedPacket p : chrono) {
            if (p.kind != PacketKind.INTERACT_ENTITY) continue;
            if (!String.valueOf(p.objA).contains("ATTACK")) continue;
            if (p.seq <= seen) continue;
            long ta = p.tickIndex;
            // Need the post-snap frames to have arrived; if not, stop here and
            // re-evaluate this attack on a later tick (don't advance past it).
            if (newestFrame - ta < returnWin) break;
            if (p.seq > max) max = p.seq;

            Entity victim = Combat.resolveById(ctx.player, p.intA, 8.0);
            if (!(victim instanceof LivingEntity) || victim == ctx.player) continue;

            PositionFrame at = byTick.get(ta);
            PositionFrame before = byTick.get(ta - 2);
            if (at == null || before == null) continue;
            any = true;

            double snap = 0;
            for (long t = ta - 1; t <= ta; t++) {
                PositionFrame g = byTick.get(t);
                if (g != null) snap = Math.max(snap, g.dYaw);
            }
            // True client rotation at attack send time (packet-stamped), falling
            // back to the frame's yaw. Victim hitbox rewound to the attack
            // instant — desync between hit and eval would otherwise inflate err
            // on a fast-moving victim and silently mask the snap signature.
            float useYaw   = p.hasRot ? p.yaw   : at.yaw;
            float usePitch = p.hasRot ? p.pitch : at.pitch;
            double[] box = ctx.state.targets.boxAt(p.intA, p.recvNanos);
            double err = box != null
                    ? Combat.aimAngle(at.x, at.y + Combat.eyeHeight(ctx.player),
                            at.z, useYaw, usePitch, box)
                    : Combat.aimAngle(at.x, at.y + Combat.eyeHeight(ctx.player),
                            at.z, useYaw, usePitch, victim);

            // Did the yaw return near its pre-snap value after the hit?
            double bestReturn = Double.MAX_VALUE;
            for (long t = ta + 1; t <= ta + returnWin; t++) {
                PositionFrame g = byTick.get(t);
                if (g == null) continue;
                bestReturn = Math.min(bestReturn,
                        Math.abs(MathUtil.wrapAngle(g.yaw - before.yaw)));
            }

            // NOTE: a "zero yaw delta + dead-on hit" silent-aura path was
            // considered and rejected. From the server's perspective a silent
            // aura's SENT yaw is already on-target, which is INDISTINGUISHABLE
            // from a legitimate stationary engagement — a player walking
            // straight at a target they're already aimed at lands repeated
            // dead-on hits with zero mouse movement. Flagging that is a false
            // positive. Silent-aim is correctly owned by AimI (rotation vs
            // movement desync), AimF (GCD) and SpoofedRotation, which CAN tell
            // them apart. AimD stays strictly the snap-and-return signature.
            if (snap >= snapMin && err <= lockCone
                    && bestReturn <= returnEps) {
                flagged = true;
                if (diverge(ctx, cfgD("score", 7.0), cfgD("threshold", 9.0),
                        cfgI("min-streak", 3),
                        String.format("snap %.0f°→%.1f° then back %.1f°",
                                snap, err, bestReturn), false)) {
                    armCombatCancel(ctx);
                }
            }
        }
        lastSeq.put(id, max);
        if (!flagged && any) clean(ctx, 1.0);
    }

    @Override
    public void cleanup(UUID uuid) {
        super.cleanup(uuid);
        lastSeq.remove(uuid);
    }
}
