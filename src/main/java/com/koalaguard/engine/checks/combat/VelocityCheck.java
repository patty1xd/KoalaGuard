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
        double peakH = 0, maxUp = 0;
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
                k.peakH = 0;
                k.maxUp = 0;
            }
        }

        if (k.kbTick == Long.MIN_VALUE) return;

        PositionFrame f = ctx.state.current;
        if (f != null && f.tick > k.kbTick) {
            k.peakH = Math.max(k.peakH, f.horizontalSpeed());
            k.maxUp = Math.max(k.maxUp, f.dy);
        }

        if (ctx.state.tick < k.evalTick) return;

        // Evaluate the absorbed knockback against the simulated requirement.
        combat.knockbackConsumed = true;
        long kbTick = k.kbTick;
        k.kbTick = Long.MIN_VALUE;

        if (ctx.unstableBasic()) return;

        double frac = k.expectedH <= 0 ? 1.0 : k.peakH / k.expectedH;
        double minFrac = cfgD("min-fraction", 0.33);
        if (frac < minFrac) {
            diverge(ctx, (minFrac - frac) * cfgD("score-scale", 22.0),
                    cfgD("threshold", 9.0), cfgI("min-streak", 3),
                    String.format("absorbed %.0f%% of simulated knockback (peakH=%.3f exp=%.3f)",
                            frac * 100, k.peakH, k.expectedH), false);
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
