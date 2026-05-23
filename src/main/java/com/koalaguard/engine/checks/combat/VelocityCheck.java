package com.koalaguard.engine.checks.combat;

import com.koalaguard.KoalaGuard;
import com.koalaguard.check.CheckCategory;
import com.koalaguard.engine.check.CheckContext;
import com.koalaguard.engine.check.SimCheck;
import com.koalaguard.engine.sim.PhysicsSimulator;
import com.koalaguard.engine.state.PositionFrame;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Anti-knockback / Velocity. When the server applies a knockback we SIMULATE
 * the travel the client is obliged to take, then watch the reconstructed
 * frames over the following ticks. If the player consistently absorbs far less
 * than the simulated knockback, that is a velocity modifier — proven against
 * the simulation, not timed against the hit.
 */
public final class VelocityCheck extends SimCheck {

    private static final class K {
        long kbTick = Long.MIN_VALUE;
        long evalTick;
        double expectedH, expectedY;
        double kbDirX, kbDirZ;            // unit vector along the kb direction
        double peakAlong = 0, maxUp = 0;  // signed projection onto kb direction
        double maxAgainst = 0;            // signed projection AGAINST kb (reversal)
    }

    private final Map<UUID, K> pend = new ConcurrentHashMap<>();

    public VelocityCheck(KoalaGuard plugin) {
        super(plugin, "velocity", CheckCategory.COMBAT, "Reduced/cancelled server knockback");
    }

    @Override
    public void onTick(CheckContext ctx) {
        var combat = ctx.state.combat;
        UUID id = ctx.data.getUuid();
        K k = pend.computeIfAbsent(id, x -> new K());

        // Arm on a fresh, meaningful knockback.
        if (combat.pendingKnockback != null
                && combat.knockbackTick != k.kbTick
                && !combat.knockbackConsumed) {
            double[] exp = PhysicsSimulator.expectedKnockbackTravel(combat.pendingKnockback);
            if (exp[0] >= cfgD("min-knockback", 0.10)) {
                k.kbTick = combat.knockbackTick;
                k.evalTick = ctx.state.tick + cfgI("window-ticks", 6);
                k.expectedH = exp[0];
                k.expectedY = exp[1];
                k.peakAlong = 0;
                k.maxAgainst = 0;
                k.maxUp = 0;
                double mag = Math.sqrt(combat.pendingKnockback.getX() * combat.pendingKnockback.getX()
                                     + combat.pendingKnockback.getZ() * combat.pendingKnockback.getZ());
                if (mag > 1e-6) {
                    k.kbDirX = combat.pendingKnockback.getX() / mag;
                    k.kbDirZ = combat.pendingKnockback.getZ() / mag;
                } else { k.kbDirX = 0; k.kbDirZ = 0; }
            }
        }

        if (k.kbTick == Long.MIN_VALUE) return;

        PositionFrame f = ctx.state.current;
        if (f != null && f.tick > k.kbTick) {
            // SIGNED projection onto the kb direction — catches Reversal
            // (player moves the WRONG way; scalar speed would still look
            // healthy) and JumpReset attenuation. peakAlong only grows on
            // motion ACTUALLY in the kb direction.
            double along = f.dx * k.kbDirX + f.dz * k.kbDirZ;
            if (along > k.peakAlong) k.peakAlong = along;
            if (-along > k.maxAgainst) k.maxAgainst = -along;
            k.maxUp = Math.max(k.maxUp, f.dy);
        }

        if (ctx.state.tick < k.evalTick) return;

        // Evaluate the absorbed knockback against the simulated requirement.
        combat.knockbackConsumed = true;
        long kbTick = k.kbTick;
        k.kbTick = Long.MIN_VALUE;

        if (ctx.unstableBasic()) return;

        double frac = k.expectedH <= 0 ? 1.0 : k.peakAlong / k.expectedH;
        double minFrac = cfgD("min-fraction", 0.33);

        // Reversal: signed projection went strongly NEGATIVE — the player
        // moved AGAINST the knockback direction. Counter-sprint W-tap PvP nets
        // small against-kb motion once friction overtakes the impulse, so
        // raised the magnitude floor AND require a substantial kb (>0.35)
        // before flagging — small kb produces small fractions either way.
        if (k.maxAgainst > cfgD("max-against", 0.35) && k.expectedH > 0.35) {
            diverge(ctx, cfgD("reversal-score", 8.0),
                    cfgD("threshold", 9.0), cfgI("min-streak", 3),
                    String.format("kb reversal: moved %.3f against kb dir (exp=%.3f)",
                            k.maxAgainst, k.expectedH), false);
            return;
        }

        // Fraction-absorbed gate: gate on a minimum expectedH so tiny kb
        // (sub-significance) doesn't spuriously produce fractions that look
        // small in absolute terms but are noise.
        double minExpected = cfgD("min-expected-h", 0.22);
        if (k.expectedH >= minExpected && frac < minFrac) {
            diverge(ctx, (minFrac - frac) * cfgD("score-scale", 22.0),
                    cfgD("threshold", 9.0), cfgI("min-streak", 3),
                    String.format("absorbed %.0f%% of simulated knockback (along=%.3f exp=%.3f)",
                            frac * 100, k.peakAlong, k.expectedH), false);
        } else {
            clean(ctx, 2.0);
        }
    }

    @Override
    public void cleanup(UUID uuid) {
        super.cleanup(uuid);
        pend.remove(uuid);
    }
}
