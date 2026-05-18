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
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.util.BoundingBox;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Aim B — through-wall hit. INDEPENDENT aim check.
 *
 * The reconstructed eye→victim segment is obstructed by a full solid block.
 * You cannot melee an entity through a wall. Edge cases (clipping a corner,
 * victim hugging a block) are suppressed by skipping very close victims and by
 * requiring persistence, so a real fight against someone next to cover never
 * confirms.
 */
public final class AimB extends SimCheck {

    private final Map<UUID, Long> lastSeq = new ConcurrentHashMap<>();

    public AimB(KoalaGuard plugin) {
        super(plugin, "aimb", CheckCategory.COMBAT, "Attack through a solid block");
    }

    @Override
    public void onTick(CheckContext ctx) {
        if (ctx.unstableBasic()) return;
        UUID id = ctx.data.getUuid();
        long seen = lastSeq.getOrDefault(id, Long.MIN_VALUE);
        long max = seen;

        List<CapturedPacket> recent = ctx.state.log.recent(160);
        List<CapturedPacket> chrono = new ArrayList<>(recent);
        java.util.Collections.reverse(chrono);

        double minDist = cfgD("min-dist", 1.6);
        boolean flagged = false, any = false;

        for (CapturedPacket p : chrono) {
            if (p.kind != PacketKind.INTERACT_ENTITY) continue;
            if (!String.valueOf(p.objA).contains("ATTACK")) continue;
            if (p.seq > max) max = p.seq;
            if (p.seq <= seen) continue;

            Entity victim = Combat.resolveById(ctx.player, p.intA, 8.0);
            if (!(victim instanceof LivingEntity) || victim == ctx.player) continue;

            PositionFrame f = ctx.state.frameAtOrBefore(p.tickIndex);
            double[] el = Combat.eyeLook(f, ctx.player);
            double ex = el[0], ey = el[1], ez = el[2];

            double[] box = ctx.state.targets.boxAt(p.intA, p.recvNanos);
            double cx, cy, cz, dist;
            if (box != null) {
                dist = Combat.distanceToBox(ex, ey, ez, box);
                cx = (box[0] + box[3]) / 2.0;
                cy = (box[1] + box[4]) / 2.0;
                cz = (box[2] + box[5]) / 2.0;
            } else {
                dist = Combat.distanceToBox(ex, ey, ez, victim);
                BoundingBox b = victim.getBoundingBox();
                cx = b.getCenterX(); cy = b.getCenterY(); cz = b.getCenterZ();
            }
            if (dist < minDist) continue;                    // corner/edge case
            any = true;

            if (CollisionEngine.rayBlocked(ctx.player.getWorld(), ex, ey, ez,
                    cx, cy, cz)) {
                flagged = true;
                if (diverge(ctx, cfgD("score", 5.0), cfgD("threshold", 9.0),
                        cfgI("min-streak", 3),
                        String.format("through-wall hit dist=%.2f", dist), false)) {
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
