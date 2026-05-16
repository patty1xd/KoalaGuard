package com.koalaguard.engine.checks.combat;

import com.koalaguard.KoalaGuard;
import com.koalaguard.check.CheckCategory;
import com.koalaguard.engine.check.CheckContext;
import com.koalaguard.engine.check.SimCheck;
import com.koalaguard.util.MathUtil;

import java.util.ArrayList;
import java.util.List;

/**
 * Rotation plausibility OVER TIME. Never "this one yaw delta is too big".
 * Across a window of reconstructed aim deltas, while the player is engaged in
 * combat, it looks for the machine signatures a human mouse cannot produce:
 *   • collapsed entropy in the delta distribution (robotic consistency),
 *   • a stable non-zero GCD granularity (quantised aimbot stepping),
 *   • a hard snap immediately followed by an unnaturally locked aim.
 * Each is computed from many samples and only a sustained pattern is confirmed.
 */
public final class RotationCheck extends SimCheck {

    public RotationCheck(KoalaGuard plugin) {
        super(plugin, "rotation", CheckCategory.COMBAT, "Implausible aim pattern over time");
    }

    @Override
    public void onTick(CheckContext ctx) {
        if (ctx.unstableBasic()) return;

        // Only meaningful while actually fighting — looking around the world is
        // legitimately low-entropy, so we never analyse it out of combat.
        long sinceAtk = ctx.state.tick - ctx.state.combat.lastAttackTick;
        if (ctx.state.combat.lastAttackTick < 0
                || sinceAtk > cfgI("combat-window-ticks", 50)) {
            clean(ctx, 0.5);
            return;
        }

        int n = cfgI("samples", 40);
        List<Float> yd = ctx.state.yawDeltas(n);
        List<Float> pd = ctx.state.pitchDeltas(n);
        if (yd.size() < cfgI("min-samples", 26)) return;

        // Consider only ticks where the player was actually turning.
        List<Double> active = new ArrayList<>();
        for (float v : yd) if (v > 0.05f && v < 70f) active.add((double) v);
        if (active.size() < cfgI("min-active", 16)) { clean(ctx, 0.5); return; }

        double entropy = MathUtil.entropy(active);
        double gcd = MathUtil.seriesGcd(active);
        double pitchSd = MathUtil.standardDeviation(pd);

        double bad = 0;
        StringBuilder why = new StringBuilder();

        if (entropy < cfgD("min-entropy", 1.35)) {
            bad += (cfgD("min-entropy", 1.35) - entropy) * cfgD("entropy-scale", 18.0);
            why.append(String.format("entropy=%.2f ", entropy));
        }
        if (gcd > cfgD("min-gcd", 0.30) && active.size() >= 20) {
            bad += cfgD("gcd-score", 5.0);
            why.append(String.format("gcd=%.3f ", gcd));
        }
        // Aimbot "snap then lock": large mean turn but near-zero pitch noise.
        if (MathUtil.average(active) > cfgD("snap-mean", 12.0)
                && pitchSd < cfgD("max-pitch-sd", 0.06)) {
            bad += cfgD("snaplock-score", 5.0);
            why.append(String.format("snap-lock pSd=%.3f ", pitchSd));
        }

        if (bad > 0) {
            diverge(ctx, bad, cfgD("threshold", 16.0), cfgI("min-streak", 5),
                    "aim pattern " + why.toString().trim(), false);
        } else {
            clean(ctx, 1.0);
        }
    }
}
