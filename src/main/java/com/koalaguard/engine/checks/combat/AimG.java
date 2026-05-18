package com.koalaguard.engine.checks.combat;

import com.koalaguard.KoalaGuard;
import com.koalaguard.check.CheckCategory;
import com.koalaguard.engine.check.CheckContext;
import com.koalaguard.engine.check.SimCheck;
import com.koalaguard.engine.packet.CapturedPacket;
import com.koalaguard.engine.packet.PacketKind;
import com.koalaguard.util.MathUtil;

import java.util.ArrayList;
import java.util.List;

/**
 * Aim G — rotation smoothness / tremor entropy. INDEPENDENT aim check.
 *
 * The other half of catching Meteor-style silent aim (which sends a perfectly
 * legal on-target rotation, so geometry can't help): a real hand ALWAYS has
 * micro-tremor — the jerk (3rd derivative of yaw) changes sign constantly and
 * the delta distribution has high entropy. Aim-assist smoothing produces
 * mathematically clean easing curves: jerk barely changes sign and entropy
 * collapses. We require BOTH (low jerk-sign-change rate AND low entropy) over
 * a large in-combat sample with substantial turning, so legitimate smooth
 * pursuit (which still trembles) never confirms.
 */
public final class AimG extends SimCheck {

    public AimG(KoalaGuard plugin) {
        super(plugin, "aimg", CheckCategory.COMBAT, "Unnaturally smooth rotation (no tremor)");
    }

    @Override
    public void onTick(CheckContext ctx) {
        if (ctx.unstableBasic()) return;

        long combatWindowNs = cfgL("combat-window-ns", 1_500_000_000L);
        long newest = -1;
        for (CapturedPacket p : ctx.state.log.recent(96)) {
            if (p.kind == PacketKind.INTERACT_ENTITY
                    && String.valueOf(p.objA).contains("ATTACK")) { newest = p.recvNanos; break; }
        }
        if (newest < 0 || System.nanoTime() - newest > combatWindowNs) {
            clean(ctx, 0.5);
            return;
        }

        List<Float> signed = ctx.state.signedYawDeltas(cfgI("samples", 200));
        // Restrict to the actively-turning span.
        List<Double> d = new ArrayList<>();
        double turn = 0;
        for (float v : signed) {
            double a = Math.abs(v);
            if (a >= cfgD("min-active-deg", 0.04) && a <= cfgD("max-active-deg", 30.0)) {
                d.add((double) v);
                turn += a;
            }
        }
        int minSamples = cfgI("min-samples", 60);
        if (d.size() < minSamples || turn < cfgD("min-turn-sum-deg", 80.0)) {
            clean(ctx, 0.5);
            return;
        }

        // Jerk sign-change rate (human hand tremor → high).
        int jerkSign = 0, jerkN = 0;
        double prevJerk = Double.NaN;
        for (int i = 2; i < d.size(); i++) {
            double acc = d.get(i) - d.get(i - 1);
            double accPrev = d.get(i - 1) - d.get(i - 2);
            double jerk = acc - accPrev;
            if (!Double.isNaN(prevJerk) && Math.abs(jerk) > 1e-4 && Math.abs(prevJerk) > 1e-4) {
                if ((jerk > 0) != (prevJerk > 0)) jerkSign++;
                jerkN++;
            }
            prevJerk = jerk;
        }
        double signRate = jerkN > 0 ? (double) jerkSign / jerkN : 1.0;

        // Distribution entropy of the absolute deltas (human → high/spread).
        List<Double> mag = new ArrayList<>(d.size());
        for (double v : d) mag.add(Math.abs(v) * 4.0);   // ×4 → finer entropy buckets
        double entropy = MathUtil.entropy(mag);

        boolean tooSmooth = signRate < cfgD("max-jerk-sign-rate", 0.18);
        boolean lowEntropy = entropy < cfgD("max-entropy-bits", 1.6);

        if (debug()) {
            plugin.getLogger().info("[aimg] " + ctx.player.getName()
                    + " signRate=" + String.format("%.3f", signRate)
                    + " entropy=" + String.format("%.2f", entropy)
                    + " n=" + d.size() + " turn=" + String.format("%.0f", turn));
        }

        if (tooSmooth && lowEntropy) {
            if (diverge(ctx, cfgD("score", 5.0), cfgD("threshold", 12.0),
                    cfgI("min-streak", 8),
                    String.format("smooth rotation: jerkSignRate=%.3f entropy=%.2f n=%d",
                            signRate, entropy, d.size()), false)) {
                armCombatCancel(ctx);
            }
        } else {
            clean(ctx, 1.0);
        }
    }
}
