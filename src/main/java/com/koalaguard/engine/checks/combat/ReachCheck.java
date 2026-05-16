package com.koalaguard.engine.checks.combat;

import com.koalaguard.KoalaGuard;
import com.koalaguard.check.CheckCategory;
import com.koalaguard.engine.check.CheckContext;
import com.koalaguard.engine.check.SimCheck;
import com.koalaguard.engine.util.Combat;
import com.koalaguard.engine.state.PositionFrame;
import org.bukkit.entity.Entity;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Reach. The attacker's eye is REBUILT from the position frame that was
 * current on the exact tick the attack packet was bound to (the spoofable
 * side), then measured to the victim's authoritative hitbox. Transport noise
 * widens the tolerance via the lag model's jitter only — never by subtracting
 * raw ping. One long hit can be a victim-position race; only persistent
 * over-reach across hits is confirmed.
 */
public final class ReachCheck extends SimCheck {

    private final Map<UUID, Long> seen = new ConcurrentHashMap<>();

    public ReachCheck(KoalaGuard plugin) {
        super(plugin, "reach", CheckCategory.COMBAT, "Attacking beyond reconstructed reach");
    }

    @Override
    public void onTick(CheckContext ctx) {
        long atk = ctx.state.combat.lastAttackTick;
        if (atk < 0) return;
        UUID id = ctx.data.getUuid();
        if (seen.getOrDefault(id, -1L) == atk) return;   // already evaluated
        seen.put(id, atk);
        if (ctx.unstableBasic()) return;

        Entity victim = Combat.resolveById(ctx.player,
                ctx.state.combat.lastAttackEntityId, 8.0);
        if (victim == null) return;

        PositionFrame f = ctx.state.frameAtOrBefore(atk);
        double[] eye = Combat.eyeLook(f, ctx.player);
        double dist = Combat.distanceToBox(eye[0], eye[1], eye[2], victim);

        double base = cfgD("max-reach", 3.0);
        double tol = Math.min(cfgD("max-jitter-slack", 0.5),
                ctx.lag().toleranceTicks() * 0.03);
        double limit = base + tol;

        if (dist > limit) {
            diverge(ctx, (dist - limit) * cfgD("score-scale", 18.0),
                    cfgD("threshold", 9.0), cfgI("min-streak", 3),
                    String.format("reach %.3f > %.2f (eye reconstructed @tick %d)",
                            dist, limit, atk), false);
        } else {
            clean(ctx, 1.5);
        }
    }

    @Override
    public void cleanup(UUID uuid) {
        super.cleanup(uuid);
        seen.remove(uuid);
    }
}
