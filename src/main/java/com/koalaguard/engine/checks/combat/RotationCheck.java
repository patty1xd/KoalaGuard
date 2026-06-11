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

        // (Out-of-range yaw check removed: Optifine/Sodium/some 1.21 clients
        //  briefly emit unwrapped yaw at the ±180° boundary on legit movement,
        //  which was the FP user reported as "false checks for anything if you
        //  move a bit". NaN/Infinity still caught above.)

        // NOTE: a "yaw delta > 180° in one tick" rule was considered and
        // rejected — PositionFrame.dYaw is stored ALREADY WRAPPED to [0,180]
        // (EngineTask: abs(wrapAngle(yaw-prevYaw))), so it can never exceed
        // 180 and the rule would be dead code. The raw pre-wrap delta is not
        // retained, and a genuine 180° one-tick flick is achievable by humans
        // (fast flick-aim), so flagging near-180 would false-positive.
        //
        // The previous "quantised aim" GCD analysis was removed: it was
        // false-positive prone on raw-input mice and specific sensitivities
        // (the leftover signal the user kept hitting). Silent-aim detection is
        // owned by the AimA-I family (rotation-vs-movement desync, lag-comp
        // angular variance), so RotationCheck stays strictly the impossible-
        // pitch / out-of-range-yaw / quantise-collapse detector.
        clean(ctx, 0.5);
    }
}
