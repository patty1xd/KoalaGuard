package com.koalaguard.engine.checks.combat;

import com.koalaguard.KoalaGuard;
import com.koalaguard.check.CheckCategory;
import com.koalaguard.engine.check.CheckContext;
import com.koalaguard.engine.check.SimCheck;
import com.koalaguard.util.MathUtil;

import java.util.ArrayList;
import java.util.List;

/**
 * Rotation plausibility over time — REWRITTEN to be false-positive proof.
 *
 * The previous entropy / "snap-then-lock" heuristics flagged normal PvP (human
 * tracking is legitimately low-entropy and keeps pitch fairly steady), so they
 * are gone. The anti-aim job is owned by {@link HitValidationCheck} (the hit
 * must intersect the reconstructed hitbox). This check now only fires on two
 * signals a real mouse physically cannot produce:
 *
 *  1. out-of-range pitch (|pitch| > 90°) — impossible from a vanilla client;
 *  2. quantised aim — across a LARGE sample, almost every turn delta is an
 *     exact integer multiple of one fixed step (robotic rotation stepping).
 *
 * Both need a sustained streak, so a stray bad packet never confirms.
 */
public final class RotationCheck extends SimCheck {

    public RotationCheck(KoalaGuard plugin) {
        super(plugin, "rotation", CheckCategory.COMBAT, "Physically impossible aim");
    }

    @Override
    public void onTick(CheckContext ctx) {
        if (ctx.unstableBasic()) return;

        // 1) Out-of-range pitch — conclusive, no sample needed.
        float pitch = ctx.state.prevPitch;
        float yaw   = ctx.state.prevYaw;
        if (Math.abs(pitch) > 90.0001f) {
            diverge(ctx, cfgD("invalid-pitch-score", 8.0),
                    cfgD("threshold", 14.0), cfgI("min-streak", 3),
                    String.format("pitch %.2f out of [-90,90]", pitch), false);
            return;
        }

        // 1b) NaN / Infinity in rotation. Some packet-crash exploits send these
        //     to confuse anticheats or trigger server-side bugs; vanilla mouse
        //     output never produces them. Single occurrence ⇒ conclusive.
        if (Float.isNaN(pitch) || Float.isNaN(yaw)
                || Float.isInfinite(pitch) || Float.isInfinite(yaw)) {
            diverge(ctx, cfgD("invalid-pitch-score", 8.0),
                    cfgD("threshold", 14.0), cfgI("min-streak", 1),
                    "non-finite rotation yaw=" + yaw + " pitch=" + pitch, false);
            return;
        }

        // 1c) Yaw leap > 180° in a single rotation packet — physically the
        //     widest distinguishable mouse turn is ≤180° (the wrap point);
        //     anything bigger is an unwrapped value that the cheat forgot to
        //     normalise. Single sample, large margin; legit shouldn't trip.
        java.util.List<Float> signed = ctx.state.signedYawDeltas(8);
        for (float d : signed) {
            if (Math.abs(d) > cfgD("max-yaw-leap-deg", 180.0)) {
                diverge(ctx, cfgD("invalid-pitch-score", 8.0),
                        cfgD("threshold", 14.0), cfgI("min-streak", 1),
                        String.format("yaw leap %.1f° in one packet", d), false);
                return;
            }
        }

        // 2) Quantised aim — only while fighting, only on a large sample.
        long sinceAtk = ctx.state.tick - ctx.state.combat.lastAttackTick;
        if (ctx.state.combat.lastAttackTick < 0
                || sinceAtk > cfgI("combat-window-ticks", 60)) {
            clean(ctx, 0.5);
            return;
        }

        List<Float> yd = ctx.state.yawDeltas(cfgI("samples", 60));
        List<Double> active = new ArrayList<>();
        for (float v : yd) if (v > 0.05f && v < 60f) active.add((double) v);
        if (active.size() < cfgI("min-active", 45)) { clean(ctx, 0.4); return; }

        double gcd = MathUtil.seriesGcd(active);
        double minGcd = cfgD("min-gcd", 0.5);
        if (gcd < minGcd) { clean(ctx, 1.0); return; }

        // Fraction of samples that are an exact multiple of the step. A human
        // raw-input mouse produces continuous floats — this stays ~0 for them.
        double eps = cfgD("quantise-epsilon", 0.012);
        int onStep = 0;
        for (double v : active) {
            double r = Math.abs(v / gcd - Math.rint(v / gcd));
            if (r * gcd < eps) onStep++;
        }
        double frac = onStep / (double) active.size();

        if (frac >= cfgD("min-quantised-fraction", 0.97)) {
            diverge(ctx, cfgD("quantise-score", 6.0),
                    cfgD("threshold", 14.0), cfgI("min-streak", 6),
                    String.format("quantised aim: %.0f%% of %d turns are multiples of %.3f°",
                            frac * 100, active.size(), gcd), false);
        } else {
            clean(ctx, 1.0);
        }
    }
}
