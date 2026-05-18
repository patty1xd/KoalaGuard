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
        if (ctx.data.flagBadChannel) {
            diverge(ctx, cfgD("score", 20.0), cfgD("threshold", 9.0),
                    cfgI("min-streak", 1),
                    "cheat client fingerprint: " + ctx.data.badChannel
                            + " (brand=" + ctx.data.clientBrand + ")", false);
        }
    }
}
