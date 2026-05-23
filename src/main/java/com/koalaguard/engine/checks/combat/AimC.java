package com.koalaguard.engine.checks.combat;

import com.koalaguard.KoalaGuard;
import com.koalaguard.check.CheckCategory;
import com.koalaguard.engine.check.CheckContext;
import com.koalaguard.engine.check.SimCheck;
import com.koalaguard.engine.packet.CapturedPacket;
import com.koalaguard.engine.packet.PacketKind;
import com.koalaguard.engine.util.Combat;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Aim C — multi-target aura. INDEPENDENT aim check.
 *
 * Distinct LIVING entities struck within a short window. A human commits to
 * one target at a time; a kill-aura sprays every entity in range. The window
 * is short and the target count generous so cleaning up two mobs at once in
 * normal play never confirms.
 */
public final class AimC extends SimCheck {

    public AimC(KoalaGuard plugin) {
        super(plugin, "aimc", CheckCategory.COMBAT, "Multi-target kill-aura");
    }

    @Override
    public void onTick(CheckContext ctx) {
        if (ctx.unstableBasic()) return;

        long windowNs = cfgL("window-ns", 500_000_000L);
        List<CapturedPacket> recent = ctx.state.log.recent(160);

        long newest = -1;
        for (CapturedPacket p : recent) {
            if (p.kind == PacketKind.INTERACT_ENTITY
                    && String.valueOf(p.objA).contains("ATTACK")) { newest = p.recvNanos; break; }
        }
        if (newest < 0) { clean(ctx, 0.5); return; }
        // Dedupe so the SAME attack window is scored only once. Previously
        // every tick re-evaluated the same 160-packet window and re-added
        // score, snowballing the violation on a single legit AoE cleanup of
        // 3 mobs. Key off the newest attack nanos: only re-evaluate when
        // newer attacks arrived.
        java.util.UUID id = ctx.data.getUuid();
        Long prevNewest = lastScoredNanos.get(id);
        if (prevNewest != null && prevNewest == newest) { clean(ctx, 0.5); return; }
        lastScoredNanos.put(id, newest);

        Set<Integer> targets = new HashSet<>();
        for (CapturedPacket p : recent) {
            if (p.kind != PacketKind.INTERACT_ENTITY) continue;
            if (!String.valueOf(p.objA).contains("ATTACK")) continue;
            if (newest - p.recvNanos > windowNs) continue;
            Entity e = Combat.resolveById(ctx.player, p.intA, 8.0);
            if (e instanceof LivingEntity && e != ctx.player) targets.add(p.intA);
        }

        int maxT = cfgI("max-targets", 3);
        if (targets.size() >= maxT) {
            if (diverge(ctx, (targets.size() - maxT + 1) * cfgD("score", 5.0),
                    cfgD("threshold", 9.0), cfgI("min-streak", 2),
                    targets.size() + " distinct targets within window", false)) {
                armCombatCancel(ctx);
            }
        } else {
            clean(ctx, 1.0);
        }
    }

    private final java.util.Map<java.util.UUID, Long> lastScoredNanos
            = new java.util.concurrent.ConcurrentHashMap<>();

    @Override
    public void cleanup(java.util.UUID uuid) {
        super.cleanup(uuid);
        lastScoredNanos.remove(uuid);
    }
}
