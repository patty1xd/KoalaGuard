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
 * KillAura — multi-target. Vanilla sweep damage is server-side AoE (one
 * INTERACT_ENTITY); a human cannot send attack packets at several DIFFERENT
 * living entities within a fraction of a second. ≥N distinct living targets
 * struck inside a short window is aura. Aim/through-wall is handled by
 * HitValidationCheck; this catches the rotating multi-target behaviour.
 */
public final class KillAuraCheck extends SimCheck {

    public KillAuraCheck(KoalaGuard plugin) {
        super(plugin, "killaura", CheckCategory.COMBAT, "Attacking multiple targets inhumanly");
    }

    @Override
    public void onTick(CheckContext ctx) {
        if (ctx.unstableBasic()) return;
        long windowNs = cfgL("window-ns", 800_000_000L);
        long newest = -1;
        List<CapturedPacket> recent = ctx.state.log.recent(128);
        for (CapturedPacket p : recent) {
            if (p.kind == PacketKind.INTERACT_ENTITY
                    && String.valueOf(p.objA).contains("ATTACK")) { newest = p.recvNanos; break; }
        }
        if (newest < 0) { clean(ctx, 0.3); return; }

        Set<Integer> targets = new HashSet<>();
        for (CapturedPacket p : recent) {
            if (p.kind != PacketKind.INTERACT_ENTITY) continue;
            if (!String.valueOf(p.objA).contains("ATTACK")) continue;
            if (newest - p.recvNanos > windowNs) continue;
            Entity e = Combat.resolveById(ctx.player, p.intA, 8.0);
            if (e instanceof LivingEntity && e != ctx.player) targets.add(p.intA);
        }

        int limit = cfgI("max-targets", 3);
        if (targets.size() >= limit) {
            if (diverge(ctx, (targets.size() - limit + 1) * cfgD("score-scale", 5.0),
                    cfgD("threshold", 10.0), cfgI("min-streak", 2),
                    targets.size() + " distinct targets within window", false)) {
                armCombatCancel(ctx);
            }
        } else {
            clean(ctx, 1.0);
        }
    }
}
