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
 * Aim F — sensitivity / GCD quantization collapse. INDEPENDENT aim check.
 *
 * THE strongest modern signal, and the one that catches Meteor-style silent
 * aim: that cheat spoofs the rotation it SENDS the server straight onto the
 * victim (your F5 body aims at the target even though your screen looks away),
 * so every GEOMETRIC check sees a perfectly legal hit. But a real mouse can
 * only produce rotation deltas that are integer multiples of a fixed
 * sensitivity step:
 *
 *     f   = sensitivity * 0.6 + 0.2
 *     step = f*f*f * 8        →  every legit Δyaw ≈ k * step
 *
 * Over a large sample the GCD of the human's deltas converges to that real
 * step (a meaningful value, even with raw input / high DPI). A computed /
 * smoothed aimbot rotation is continuous — its deltas share NO common step, so
 * their series-GCD collapses toward the floating-point floor. A collapsed GCD
 * during sustained combat aiming is therefore a synthetic rotation.
 *
 * FP-safe: low FPS still yields quantized (just larger-k) deltas → GCD stays
 * meaningful; large sample + long streak required; only judged while actively
 * turning in combat. Tune {@code min-step} down if a raw-input player FPs.
 */
public final class AimF extends SimCheck {

    public AimF(KoalaGuard plugin) {
        super(plugin, "aimf", CheckCategory.COMBAT, "Synthetic (non-quantized) rotation");
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
        boolean inCombat = newest >= 0
                && (System.nanoTime() - newest) <= combatWindowNs;
        if (!inCombat) { clean(ctx, 0.5); return; }

        double minActive = cfgD("min-active-deg", 0.04);
        double maxActive = cfgD("max-active-deg", 30.0);
        List<Double> yaw = activeDeltas(ctx.state.yawDeltas(cfgI("samples", 200)),
                minActive, maxActive);
        List<Double> pitch = activeDeltas(ctx.state.pitchDeltas(cfgI("samples", 200)),
                minActive, maxActive);

        int minSamples = cfgI("min-samples", 50);
        if (yaw.size() < minSamples) { clean(ctx, 0.5); return; }

        double minStep = cfgD("min-step-deg", 0.05);
        double gYaw = MathUtil.seriesGcd(yaw);
        double gPitch = pitch.size() >= minSamples
                ? MathUtil.seriesGcd(pitch) : Double.MAX_VALUE;

        // Turning must be substantial (not idle micro-jitter).
        double turn = 0;
        for (double v : yaw) turn += v;
        if (turn < cfgD("min-turn-sum-deg", 60.0)) { clean(ctx, 0.5); return; }

        double bad = 0;
        StringBuilder why = new StringBuilder();
        if (gYaw < minStep) {
            bad += cfgD("score", 5.0);
            why.append(String.format("yaw GCD %.5f<%.3f (no mouse quantization) ",
                    gYaw, minStep));
        }
        if (gPitch < minStep) {                       // corroborating axis
            bad += cfgD("pitch-score", 3.0);
            why.append(String.format("pitch GCD %.5f ", gPitch));
        }

        if (debug()) {
            plugin.getLogger().info("[aimf] " + ctx.player.getName()
                    + " gYaw=" + String.format("%.5f", gYaw)
                    + " gPitch=" + String.format("%.5f", gPitch)
                    + " n=" + yaw.size() + " turn=" + String.format("%.0f", turn)
                    + " bad=" + bad);
        }

        // Alert only — never silently cancel combat. Series-GCD is genuinely
        // noisy for raw-input / high-DPI players; this is a staff signal that
        // must NOT neutralise a fight on its own (it is disabled by default).
        if (bad > 0) {
            diverge(ctx, bad, cfgD("threshold", 12.0), cfgI("min-streak", 8),
                    "synthetic rotation :: " + why.toString().trim(), false);
        } else {
            clean(ctx, 1.0);
        }
    }

    private static List<Double> activeDeltas(List<Float> in, double lo, double hi) {
        List<Double> out = new ArrayList<>(in.size());
        for (float v : in) if (v >= lo && v <= hi) out.add((double) v);
        return out;
    }
}
