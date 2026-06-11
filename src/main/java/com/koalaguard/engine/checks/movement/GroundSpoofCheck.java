package com.koalaguard.engine.checks.movement;

import com.koalaguard.KoalaGuard;
import com.koalaguard.check.CheckCategory;
import com.koalaguard.engine.check.CheckContext;
import com.koalaguard.engine.check.SimCheck;
import com.koalaguard.engine.sim.CollisionEngine;
import com.koalaguard.engine.state.PositionFrame;

/**
 * GroundSpoof — the client asserts onGround=true on a frame where the
 * reconstructed position has NO supporting geometry at all. This is the raw
 * flag a NoFall/Criticals/Spartan-bypass cheat keeps permanently set; NoFall
 * only catches it when a damaging fall is in progress, this catches the lie
 * itself, continuously.
 *
 * The engine never TRUSTS the client flag (EngineTask corroborates against
 * sim geometry before using it), so this check is pure detection — but it is
 * what turns "the engine quietly ignored your spoof" into an actual flag,
 * setback and escalation.
 *
 * FP guards: standing on entities (boats/shulkers — legitimate onGround with
 * no block geometry) is exempted by a nearby-entity probe that only runs on
 * the already-suspicious path; ghost-block desync is covered by the teleport
 * and velocity graces; and confirmation needs a sustained run of spoofed
 * frames — a NoFall module asserts ground every airborne tick, a one-off
 * desync doesn't.
 */
public final class GroundSpoofCheck extends SimCheck {

    public GroundSpoofCheck(KoalaGuard plugin) {
        super(plugin, "groundspoof", CheckCategory.MOVEMENT,
                "Client onGround flag contradicts world geometry");
    }

    @Override
    public Stage stage() { return Stage.FRAME; }

    @Override
    public void onTick(CheckContext ctx) {
        PositionFrame f = ctx.state.current;
        if (f == null) return;

        if (ctx.unstable() || ctx.state.exFlying || ctx.state.exGliding
                || ctx.state.exVehicle || ctx.state.exLiquid
                || ctx.state.exClimbing || ctx.state.exWeb
                || ctx.state.exLevitation || ctx.state.exRiptide) {
            clean(ctx, 1.0);
            return;
        }

        long now = System.currentTimeMillis();
        if (now - ctx.data.lastTeleportMs < cfgL("teleport-grace-ms", 2000L)
                || now - ctx.data.lastVelocityMs < cfgL("velocity-grace-ms", 1000L)
                || now - ctx.data.joinMs < 3500
                || now - ctx.data.lastRespawnMs < 2500) {
            clean(ctx, 1.0);
            return;
        }

        if (!f.clientGround) {
            clean(ctx, 1.0);
            return;
        }

        double h = ctx.player.isSneaking() ? 1.5 : 1.8;
        boolean supported = f.simGround
                || CollisionEngine.nearGround(ctx.player.getWorld(), f.x, f.y, f.z, h);
        if (supported) {
            clean(ctx, 1.0);
            return;
        }

        // Entity floor: standing on a boat or shulker is legitimate onGround
        // with zero block geometry. Probed only on this (rare) path.
        for (var e : ctx.player.getNearbyEntities(1.5, 3.0, 1.5)) {
            if (e instanceof org.bukkit.entity.Boat
                    || e instanceof org.bukkit.entity.Shulker) {
                clean(ctx, 1.0);
                return;
            }
        }

        diverge(ctx, cfgD("score", 4.0), cfgD("threshold", 12.0),
                cfgI("min-streak", 5),
                String.format("onGround=true with no geometry under (%.2f %.2f %.2f), dy=%.3f",
                        f.x, f.y, f.z, f.dy), true);
    }
}
