package com.koalaguard.engine.checks.combat;

import com.koalaguard.KoalaGuard;
import com.koalaguard.check.CheckCategory;
import com.koalaguard.engine.check.CheckContext;
import com.koalaguard.engine.check.SimCheck;
import com.koalaguard.engine.packet.CapturedPacket;
import com.koalaguard.engine.packet.PacketKind;
import com.koalaguard.engine.state.PositionFrame;
import com.koalaguard.engine.util.Combat;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Aim A — out-of-FOV hit. INDEPENDENT aim check.
 *
 * The attack landed on an entity that was far outside where the player was
 * looking (reconstructed eye/look at the attack tick). The vanilla client only
 * sends an attack for an entity its crosshair raytrace hit, so a hit tens of
 * degrees off-look is a no-rotate / rage / backtrack aura.
 *
 * FP-proof by margin: the threshold is huge (≈80°). Victim-position desync
 * between the hit and our evaluation is only ~15-25°, which cannot push a
 * legitimate ~10° hit past 80°. This is the workhorse and it works while
 * standing still (Combat.eyeLook falls back to the live look for a still
 * attacker).
 */
public final class AimA extends SimCheck {

    private final Map<UUID, Long> lastSeq = new ConcurrentHashMap<>();

    public AimA(KoalaGuard plugin) {
        super(plugin, "aima", CheckCategory.COMBAT, "Attack outside field of view");
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

        double maxFov = cfgD("max-fov-deg", 80.0);
        boolean flagged = false, any = false;

        for (CapturedPacket p : chrono) {
            if (p.kind != PacketKind.INTERACT_ENTITY) continue;
            if (!String.valueOf(p.objA).contains("ATTACK")) continue;
            if (p.seq > max) max = p.seq;
            if (p.seq <= seen) continue;

            Entity victim = Combat.resolveById(ctx.player, p.intA, 8.0);
            if (!(victim instanceof LivingEntity) || victim == ctx.player) continue;
            any = true;

            PositionFrame f = ctx.state.frameAtOrBefore(p.tickIndex);
            double[] el = Combat.eyeLook(f, p, ctx.player);
            // Victim rewound to the attack instant when we have it (accurate),
            // else the live hitbox (still safe — the threshold is huge).
            double[] box = ctx.state.targets.boxAt(p.intA, p.recvNanos);
            // Quantization / point-blank guard: if the reconstructed eye is
            // inside (or touching) the victim AABB, any look direction "hits"
            // it — angle to centre is meaningless. Skip rather than risk FP.
            double dist = box != null
                    ? Combat.distanceToBox(el[0], el[1], el[2], box)
                    : Combat.distanceToBox(el[0], el[1], el[2], victim);
            if (dist < 1.0E-3) continue;
            double err = box != null
                    ? Combat.aimAngle(el[0], el[1], el[2], (float) el[3], (float) el[4], box)
                    : Combat.aimAngle(el[0], el[1], el[2], (float) el[3], (float) el[4], victim);

            if (err > maxFov) {
                flagged = true;
                if (diverge(ctx, cfgD("score", 6.0), cfgD("threshold", 8.0),
                        cfgI("min-streak", 3),
                        String.format("hit %.0f° off-look (>%.0f°)", err, maxFov), false)) {
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
