package com.koalaguard.engine.checks.player;

import com.koalaguard.KoalaGuard;
import com.koalaguard.check.CheckCategory;
import com.koalaguard.engine.check.CheckContext;
import com.koalaguard.engine.check.SimCheck;

/**
 * Layer 1 — cheat-client fingerprint. INDEPENDENT check.
 *
 * The capture layer sets {@code flagBadChannel} when the client registers or
 * talks on a known cheat-client plugin channel (meteor-client, wurst,
 * liquidbounce, …) or carries a cheat brand. Vanilla and legitimate mods never
 * use those identifiers, so this is a near-zero false-positive, immediate
 * confirmation (no streak needed). The cheat outs itself.
 */
public final class CheatClientCheck extends SimCheck {

    public CheatClientCheck(KoalaGuard plugin) {
        super(plugin, "cheatclient", CheckCategory.PLAYER,
                "Known cheat-client plugin channel / brand");
    }

    @Override
    public void onTick(CheckContext ctx) {
        // CONSUME the flag — otherwise the flag latches once and the check
        // re-fires every tick at score=20/min-streak=1, banning the player in
        // seconds off a single transient channel match. Diverge once on the
        // edge; subsequent ticks of the same channel only fire if the netty
        // listener re-stamps the flag.
        if (ctx.data.flagBadChannel) {
            ctx.data.flagBadChannel = false;
            diverge(ctx, cfgD("score", 20.0), cfgD("threshold", 9.0),
                    cfgI("min-streak", 1),
                    "cheat client fingerprint: " + ctx.data.badChannel
                            + " (brand=" + ctx.data.clientBrand + ")", false);
            return;
        }

        // View-distance anomaly path: vanilla launcher / mod launchers set
        // viewDistance in the [2, 32] range. A client locked to 8 or 12 as a
        // prediction-engine constant (FDP fork, some LB rewrites) sits at the
        // exact lower-mid; we don't flag a normal-range value, only one that
        // is impossible for the vanilla settings UI (>32 or <2).
        try {
            int vd = ctx.player.getClientViewDistance();
            int min = cfgI("min-view-distance", 2);
            // Upper bound deliberately generous: Sodium / high-render mods
            // legitimately report 48-64. Only a genuinely impossible value
            // (the vanilla + modded ceiling is ~64) is flagged, so this never
            // FPs on a legit render-distance power user.
            int max = cfgI("max-view-distance", 64);
            if (vd > 0 && (vd < min || vd > max)) {
                diverge(ctx, cfgD("vd-score", 6.0), cfgD("threshold", 9.0),
                        cfgI("vd-min-streak", 2),
                        "impossible client view-distance: " + vd, false);
            }
        } catch (Throwable ignored) { }
    }
}
