package com.koalaguard.engine.checks.combat;

import com.koalaguard.KoalaGuard;
import com.koalaguard.check.CheckCategory;
import com.koalaguard.engine.check.CheckContext;
import com.koalaguard.engine.check.SimCheck;
import com.koalaguard.engine.state.PositionFrame;
import com.koalaguard.util.MathUtil;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Aim I — ROTATION-MOVEMENT DESYNC. INDEPENDENT aim check.
 *
 * <p>The strongest server-side catch for Meteor / LiquidBounce / FDP / Sigma
 * silent-aim. Per deep research of those clients' KillAura source: the cheat
 * mutates the OUTGOING movement packet's yaw/pitch fields to point at the
 * server-tracked target, but the position/velocity in the same packet is
 * computed by the vanilla client using the REAL local yaw (the user's
 * camera). So:
 *
 * <pre>
 *   reported yaw   = spoofed angle to victim
 *   dx, dz         = derived from REAL local yaw + WASD input
 *   ⇒ atan2(-dx, dz) (the "intent yaw" implicit in the velocity vector)
 *     disagrees with the reported yaw by anything OTHER than an integer
 *     multiple of 45° (the 8 valid WASD-combinations).
 * </pre>
 *
 * Vanilla movement: pressing W with yaw=θ produces dx=-sin(θ), dz=cos(θ)
 * times the walk-speed. Strafe / back / diagonals shift the intent angle
 * by exactly k·45°. So for any genuine player, {@code (reportedYaw -
 * intentYaw) mod 45} is near zero.
 *
 * Detection: sustained in-combat samples where that residual is large.
 * Statistical (mean + sd over many frames) so a single jitter doesn't flag.
 *
 * Combat-gated; only evaluates while the player IS moving (no velocity =
 * no signal); only counts after a long-enough draw of frames. Alert-only
 * (no combat-cancel) — defence-in-depth alongside AimA/B/D/E/F/G/H.
 */
public final class AimI extends SimCheck {

    private static final class S {
        final Deque<Double> residuals = new ArrayDeque<>();
        long lastTick = Long.MIN_VALUE;
    }

    private final Map<UUID, S> state = new ConcurrentHashMap<>();

    public AimI(KoalaGuard plugin) {
        super(plugin, "aimi", CheckCategory.COMBAT,
                "Rotation contradicts movement direction (silent-aim signature)");
    }

    @Override
    public void onTick(CheckContext ctx) {
        if (ctx.unstableBasic()) return;

        // Combat-gate: only inside a recent fight. A player rotating in place
        // outside combat (looking around) doesn't move and produces no signal
        // anyway, but the gate keeps the check focused and the score series
        // from bleeding into open-world play.
        long combatWindowNs = cfgL("combat-window-ns", 1_500_000_000L);
        long nowNs = System.nanoTime();
        boolean inCombat = ctx.state.combat.lastAttackNanos > Long.MIN_VALUE / 2
                && (nowNs - ctx.state.combat.lastAttackNanos) <= combatWindowNs;
        if (!inCombat) { clean(ctx, 0.5); return; }

        PositionFrame f = ctx.state.current;
        if (f == null) return;
        // Need a meaningful horizontal velocity (no movement = the player's
        // input isn't reflected in dx/dz, so no comparison is possible).
        double h = f.horizontalSpeed();
        if (h < cfgD("min-speed", 0.10)) { clean(ctx, 0.3); return; }
        // Skip ticks where external forces drive movement (knockback / slime
        // / bubble / teleport / liquid / gliding) — the velocity isn't a
        // pure WASD derivative.
        if (ctx.state.exVehicle || ctx.state.exGliding || ctx.state.exRiptide
                || ctx.state.exLevitation || ctx.state.exLiquid || ctx.state.exClimbing
                || ctx.state.exFlying) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - ctx.data.lastVelocityMs < 1500
                || now - ctx.data.lastDamageMs   < 800
                || now - ctx.data.slimeBounceMs  < 1500
                || now - ctx.data.lastSlimeOrBedMs < 1500
                || now - ctx.data.lastTeleportMs < 1500) {
            return;
        }

        UUID id = ctx.data.getUuid();
        S s = state.computeIfAbsent(id, k -> new S());
        if (f.tick == s.lastTick) return;
        s.lastTick = f.tick;

        // Compute the residual modulo 45° between reported yaw and movement
        // direction. Minecraft: pressing W at yaw=θ produces dx=-sin(θ),
        // dz=cos(θ) — so the "intent yaw" (the yaw the movement implies) is
        // atan2(-dx, dz). 8 WASD combinations shift by k·45°.
        double moveYawDeg = Math.toDegrees(Math.atan2(-f.dx, f.dz));
        double reported   = f.yaw;
        double diff = reported - moveYawDeg;
        diff = MathUtil.wrapAngle((float) diff);                    // [-180, +180]
        // Reduce to nearest multiple of 45° — the residual.
        double residual = Math.abs(diff - 45.0 * Math.round(diff / 45.0));

        s.residuals.addLast(residual);
        while (s.residuals.size() > cfgI("sample-cap", 60)) s.residuals.removeFirst();

        int minSamples = cfgI("min-samples", 36);
        if (s.residuals.size() < minSamples) return;

        double mean = MathUtil.average(s.residuals);
        double sd   = MathUtil.standardDeviation(s.residuals);
        double meanCap = cfgD("max-mean-residual", 10.0);
        double sdMin   = cfgD("min-sd-residual",   0.0);   // unused by default

        // Real human play: residual mean ≈ 1-4° (mouse drift between WASD
        // cardinals + slight diagonal pressing), sd ≈ 2-5°. Silent aim with
        // spoofed yaw irrelevant to movement: residual is essentially uniform
        // [0, 22.5°] → mean ≈ 11°. We require BOTH a large mean residual AND
        // enough samples — a transient spike from a quick flick doesn't fire.
        //
        // NOTE: a "movement-fix" inverse path (flag TOO-perfect residual:
        // mean≈0, sd≈0) was considered and rejected. A player simply walking
        // in a straight line while looking in the direction of travel ALSO
        // produces a near-zero, low-variance residual, so it is
        // indistinguishable from a movement-fix cheat by residual alone and
        // would false-positive on ordinary straight-line combat movement.
        boolean directDesync = mean > meanCap && sd > sdMin;

        if (directDesync) {
            diverge(ctx, cfgD("score", 6.0), cfgD("threshold", 11.0),
                    cfgI("min-streak", 4),
                    String.format("rotation-movement desync: mean=%.1f° sd=%.1f° n=%d",
                            mean, sd, s.residuals.size()),
                    false);
        } else {
            clean(ctx, 1.0);
        }
    }

    @Override
    public void cleanup(UUID uuid) {
        super.cleanup(uuid);
        state.remove(uuid);
    }
}
