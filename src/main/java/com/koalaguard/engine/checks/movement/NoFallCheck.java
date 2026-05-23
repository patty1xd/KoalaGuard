package com.koalaguard.engine.checks.movement;

import com.koalaguard.KoalaGuard;
import com.koalaguard.check.CheckCategory;
import com.koalaguard.engine.check.CheckContext;
import com.koalaguard.engine.check.SimCheck;
import com.koalaguard.engine.sim.CollisionEngine;
import com.koalaguard.engine.state.PositionFrame;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * NoFall. Reconstructs the fall the player actually took from the vertical
 * deltas, then detects the impossible transition: the client asserts
 * onGround=true (cancelling fall damage) while the reconstructed position is
 * NOT geometrically supported and a damaging fall was in progress. It is a
 * state-transition contradiction, not a "did they take damage" probe.
 */
public final class NoFallCheck extends SimCheck {

    private final Map<UUID, Double> fall = new ConcurrentHashMap<>();

    public NoFallCheck(KoalaGuard plugin) {
        super(plugin, "nofall", CheckCategory.MOVEMENT, "Spoofed ground state to cancel fall damage");
    }

    @Override
    public Stage stage() { return Stage.FRAME; }

    @Override
    public void onTick(CheckContext ctx) {
        PositionFrame f = ctx.state.current;
        if (f == null) return;
        UUID id = ctx.data.getUuid();

        if (ctx.unstable() || ctx.state.exFlying || ctx.state.exGliding
                || ctx.state.exLiquid || ctx.state.exClimbing
                || ctx.state.exLevitation || ctx.state.exSlowFalling) {
            fall.put(id, 0.0);
            clean(ctx, 1.0);
            return;
        }

        double acc = fall.getOrDefault(id, 0.0);
        boolean supported = f.simGround
                || CollisionEngine.nearGround(ctx.player.getWorld(), f.x, f.y, f.z,
                        ctx.player.isSneaking() ? 1.5 : 1.8);

        if (f.dy < -0.08 && !supported) {
            acc += -f.dy;                              // accumulate the real fall
        }

        // Spoofed ground: client says grounded mid-air after a damaging fall.
        if (f.clientGround && !supported && acc > cfgD("min-fall", 3.2)) {
            diverge(ctx, cfgD("score", 6.0), cfgD("threshold", 12.0),
                    cfgI("min-streak", 2),
                    String.format("onGround spoof after %.1f-block fall", acc), true);
        } else if (acc > cfgD("min-fall", 3.2)
                && f.dy > cfgD("teleport-up-dy", 0.50)
                && !ctx.state.exGliding
                && ctx.state.tick - ctx.state.combat.knockbackTick > 6) {
            // ─── TeleportNoFall ───
            // After a damaging fall has been accumulated, a frame whose dy is
            // strongly POSITIVE (>0.5 — well past a vanilla jump that would
            // need a ground impulse) is a TELEPORT during the fall — the
            // cheat snaps the player up to "reset" the server's fallDistance.
            // Excluded: legit knockback (lastVelocityMs grace is up the stack
            // via ctx.unstable(); plus we double-gate on knockbackTick).
            diverge(ctx, cfgD("teleport-score", 8.0), cfgD("threshold", 12.0),
                    cfgI("teleport-min-streak", 1),
                    String.format("TeleportNoFall: dy=%.2f after %.1f-block fall",
                            f.dy, acc), true);
            acc = 0;
        } else {
            if (supported) { acc = 0; clean(ctx, 2.0); }
        }
        fall.put(id, acc);
    }

    @Override
    public void cleanup(UUID uuid) {
        super.cleanup(uuid);
        fall.remove(uuid);
    }
}
