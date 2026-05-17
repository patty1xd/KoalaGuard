package com.koalaguard.engine.checks.combat;

import com.koalaguard.KoalaGuard;
import com.koalaguard.check.CheckCategory;
import com.koalaguard.engine.check.CheckContext;
import com.koalaguard.engine.check.SimCheck;
import com.koalaguard.engine.sim.CollisionEngine;
import com.koalaguard.engine.state.PositionFrame;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Criticals (packet / mini-jump). A real critical needs a genuine descending
 * jump arc. Crit hacks fake it: a TINY upward blip (≈0.0625–0.13) right before
 * the hit while the player never actually leaves the ground (collision shows
 * ground support throughout, net Y ≈ unchanged).
 *
 *  • normal ground hit  → no upward blip at all          → not flagged
 *  • real jump-crit      → dy ≈ 0.42, genuinely airborne  → not flagged
 *  • packet/mini-jump    → tiny blip, never left ground   → flagged (sustained)
 */
public final class CriticalsCheck extends SimCheck {

    private final Map<UUID, Long> seen = new ConcurrentHashMap<>();

    public CriticalsCheck(KoalaGuard plugin) {
        super(plugin, "criticals", CheckCategory.COMBAT, "Faked critical hit");
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
                || ctx.state.exLevitation || ctx.state.exGliding || ctx.state.exWeb) return;

        double h = ctx.player.isSneaking() ? 1.5 : 1.8;
        int win = cfgI("window-ticks", 4);
        double maxUp = 0;
        boolean sawDown = false, leftGround = false, haveFrames = false;
        for (PositionFrame f : ctx.state.recentFrames(12)) {
            if (f.tick > atk || f.tick < atk - win) continue;
            haveFrames = true;
            maxUp = Math.max(maxUp, f.dy);
            if (f.dy < -0.005) sawDown = true;
            if (!CollisionEngine.nearGround(ctx.player.getWorld(), f.x, f.y, f.z, h))
                leftGround = true;
        }
        if (!haveFrames) return;

        // A fake hop: small positive blip, a return down, never genuinely
        // airborne. A real jump exceeds maxHop and leaves the ground.
        boolean phantom = maxUp >= cfgD("min-hop", 0.018)
                && maxUp <= cfgD("max-hop", 0.15)
                && sawDown && !leftGround;

        if (phantom) {
            diverge(ctx, cfgD("score", 4.0), cfgD("threshold", 10.0),
                    cfgI("min-streak", 5),
                    String.format("crit fake-hop maxUp=%.4f (never left ground)", maxUp),
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
