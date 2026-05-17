package com.koalaguard.engine.checks.combat;

import com.koalaguard.KoalaGuard;
import com.koalaguard.check.CheckCategory;
import com.koalaguard.engine.check.CheckContext;
import com.koalaguard.engine.check.SimCheck;
import com.koalaguard.engine.state.PositionFrame;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Criticals. A vanilla critical requires the attacker to be genuinely
 * airborne and descending. Packet-/no-slow-criticals attack while the SERVER
 * sees the player solidly on the ground (no real fall in progress). We confirm
 * only when, across many hits, the attacker was server-grounded with no
 * downward motion at the attack tick — conservative + persistent so a normal
 * jump-crit (really airborne) never trips it.
 */
public final class CriticalsCheck extends SimCheck {

    private final Map<UUID, Long> seen = new ConcurrentHashMap<>();

    public CriticalsCheck(KoalaGuard plugin) {
        super(plugin, "criticals", CheckCategory.COMBAT, "Critical hit while grounded");
    }

    @Override
    public void onTick(CheckContext ctx) {
        long atk = ctx.state.combat.lastAttackTick;
        if (atk < 0) return;
        UUID id = ctx.data.getUuid();
        if (seen.getOrDefault(id, -1L) == atk) return;
        seen.put(id, atk);
        if (ctx.unstableBasic()) return;
        if (ctx.state.exVehicle || ctx.state.exClimbing || ctx.state.exLiquid
                || ctx.state.exLevitation || ctx.state.exGliding) return;

        PositionFrame f = ctx.state.frameAtOrBefore(atk);
        if (f == null) return;

        // Packet-criticals fingerprint: the client claims it is AIRBORNE
        // (onGround=false → server grants the crit) while server collision
        // says the player is solidly on the ground and not actually falling.
        // A real jump-crit has simGround=false (genuinely airborne) → safe.
        boolean spoofedAirborne = f.simGround && !f.clientGround && f.dy >= -0.02;
        if (spoofedAirborne) {
            diverge(ctx, cfgD("score", 4.0), cfgD("threshold", 10.0),
                    cfgI("min-streak", 6),
                    String.format("crit spoof: server-grounded, client airborne dy=%.3f", f.dy),
                    false);
        } else {
            clean(ctx, 2.0);
        }
    }

    @Override
    public void cleanup(UUID uuid) {
        super.cleanup(uuid);
        seen.remove(uuid);
    }
}
