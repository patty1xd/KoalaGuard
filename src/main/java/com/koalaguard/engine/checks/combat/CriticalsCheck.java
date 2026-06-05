package com.koalaguard.engine.checks.combat;

import com.koalaguard.KoalaGuard;
import com.koalaguard.check.CheckCategory;
import com.koalaguard.engine.check.CheckContext;
import com.koalaguard.engine.check.SimCheck;
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
    private final Map<UUID, Long> seenCap = new ConcurrentHashMap<>();

    public CriticalsCheck(KoalaGuard plugin) {
        super(plugin, "criticals", CheckCategory.COMBAT, "Faked critical hit");
    }

    @Override
    public void onTick(CheckContext ctx) {
        // ── Variant 1: damage-time PREVENTION. The HIGHEST-priority handler
        // already stripped the crit multiplier on attacks where the server
        // believed a fall happened but the engine's collision truth said
        // grounded — this catches every cheat variant (NoGround/Packet/Mini-
        // Jump/Blink/Timer) at the damage edge, not via packet pattern.
        long capNs = ctx.state.combat.lastFakeCritNanos;
        UUID id0 = ctx.data.getUuid();
        if (capNs > Long.MIN_VALUE / 2 && seenCap.getOrDefault(id0, Long.MIN_VALUE) != capNs) {
            seenCap.put(id0, capNs);
            if (diverge(ctx, cfgD("cap-score", 8.0), cfgD("threshold", 10.0),
                    cfgI("cap-min-streak", 1),
                    "fake crit (no real fall) :: " + ctx.state.combat.lastFakeCritDetail,
                    false)) {
                armCombatCancel(ctx);
            }
        }

        long atk = ctx.state.combat.lastAttackTick;
        if (atk < 0) return;
        // Dedupe on unique attack-nanos, not the (stationary-frozen) tick.
        long atkNs = ctx.state.combat.lastAttackNanos;
        UUID id = ctx.data.getUuid();
        if (seen.getOrDefault(id, -1L) == atkNs) return;
        seen.put(id, atkNs);
        if (ctx.unstableBasic()) return;
        if (ctx.state.exVehicle || ctx.state.exClimbing || ctx.state.exLiquid
                || ctx.state.exLevitation || ctx.state.exGliding || ctx.state.exWeb) return;

        int win = cfgI("window-ticks", 4);
        double maxUp = 0, maxDown = 0;
        boolean sawDown = false, leftGround = false, haveFrames = false;
        for (PositionFrame f : ctx.state.recentFrames(12)) {
            if (f.tick > atk || f.tick < atk - win) continue;
            haveFrames = true;
            maxUp = Math.max(maxUp, f.dy);
            maxDown = Math.min(maxDown, f.dy);
            if (f.dy < -0.005) sawDown = true;
            // PRECISE ground: a real jump/air crit genuinely loses top-face
            // support (simGround=false) on the arc — use that, NOT the lenient
            // 1-block nearGround, which made legit low jump-crits false-flag.
            if (!f.simGround) leftGround = true;
        }
        if (!haveFrames) return;

        // A FAKE hop is a tiny up-blip that returns down only slightly and
        // NEVER actually leaves the ground. A real crit (jump OR from air)
        // either leaves the ground or has a genuine fall (dy well below the
        // micro range) — both exclude it here, so legit crits don't flag.
        boolean realFall = maxDown < -cfgD("max-fall", 0.20);
        boolean phantom = maxUp >= cfgD("min-hop", 0.018)
                && maxUp <= cfgD("max-hop", 0.15)
                && sawDown && !leftGround && !realFall;

        if (phantom) {
            diverge(ctx, cfgD("score", 4.0), cfgD("threshold", 10.0),
                    cfgI("min-streak", 5),
                    String.format("crit fake-hop maxUp=%.4f (never left ground)", maxUp),
                    false);
        } else {
            clean(ctx, 2.0);
        }

        // Vehicle-dismount crit exploit: dismounting resets server fall
        // distance, so a crit landing inside the dismount-grace window has
        // no real arc behind it. The frame-side phantom check above misses
        // this because the dismount also makes simGround flicker — so check
        // it independently. Legitimate post-dismount crits need a full arc
        // (handled by the prior haveFrames+leftGround+realFall path).
        long sinceDismountMs = System.currentTimeMillis() - ctx.state.combat.lastVehicleExitMs;
        if (ctx.state.combat.lastVehicleExitMs > 0
                && sinceDismountMs < cfgL("dismount-grace-ms", 200L)
                && !leftGround && !realFall) {
            diverge(ctx, cfgD("dismount-score", 5.0), cfgD("threshold", 10.0),
                    cfgI("dismount-min-streak", 2),
                    String.format("crit within %dms of dismount with no fall arc",
                            sinceDismountMs),
                    false);
        }
    }

    @Override
    public void cleanup(UUID uuid) {
        super.cleanup(uuid);
        seen.remove(uuid);
    }
}
