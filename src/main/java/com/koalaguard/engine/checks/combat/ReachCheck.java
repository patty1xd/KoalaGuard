package com.koalaguard.engine.checks.combat;

import com.koalaguard.KoalaGuard;
import com.koalaguard.check.CheckCategory;
import com.koalaguard.engine.check.CheckContext;
import com.koalaguard.engine.check.SimCheck;
import com.koalaguard.engine.util.Combat;
import com.koalaguard.engine.state.PositionFrame;
import org.bukkit.entity.Entity;

import java.util.List;
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

        int vid = ctx.state.combat.lastAttackEntityId;
        Entity victim = Combat.resolveById(ctx.player, vid, 8.0);
        if (victim == null) return;

        PositionFrame f = ctx.state.frameAtOrBefore(atk);
        double[] eye = Combat.eyeLook(f, ctx.player);

        // PROPER lag compensation: the victim could have been at ANY of its
        // recent positions within the network-uncertainty window. Take the
        // SMALLEST eye→hitbox distance over that window — only flag if the hit
        // is out of reach even for the most favourable victim position. This
        // is what removes the killaura-range false positives: reconstruction
        // jitter (~0.2-0.4) no longer pushes a legit 3.0 hit over the limit.
        long atkNs = ctx.state.combat.lastAttackNanos;
        long winNs = cfgL("lag-window-ms", 200L) * 1_000_000L;
        double dist = Double.MAX_VALUE;
        List<double[]> hist = ctx.state.targets.history(vid);
        for (double[] s : hist) {
            if (Math.abs((long) s[0] - atkNs) > winNs) continue;
            double[] bx = { s[1], s[2], s[3], s[4], s[5], s[6] };
            dist = Math.min(dist, Combat.distanceToBox(eye[0], eye[1], eye[2], bx));
        }
        if (dist == Double.MAX_VALUE) {                 // no snapshot in window
            double[] box = ctx.state.targets.boxAt(vid, atkNs);
            dist = box != null
                    ? Combat.distanceToBox(eye[0], eye[1], eye[2], box)
                    : Combat.distanceToBox(eye[0], eye[1], eye[2], victim);
        }

        double base = cfgD("max-reach", 3.0);
        double tol = Math.min(cfgD("max-jitter-slack", 0.5),
                ctx.lag().toleranceTicks() * 0.03)
                + cfgD("recon-slack", 0.30);            // eye-reconstruction error
        double limit = base + tol;

        if (dist > limit) {
            if (diverge(ctx, (dist - limit) * cfgD("score-scale", 18.0),
                    cfgD("threshold", 9.0), cfgI("min-streak", 3),
                    String.format("reach %.3f > %.2f (eye reconstructed @tick %d)",
                            dist, limit, atk), false)) {
                armCombatCancel(ctx);
            }
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
