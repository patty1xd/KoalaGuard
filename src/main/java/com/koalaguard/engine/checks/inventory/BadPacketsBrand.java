package com.koalaguard.engine.checks.inventory;

import com.koalaguard.KoalaGuard;
import com.koalaguard.check.CheckCategory;
import com.koalaguard.engine.check.CheckContext;
import com.koalaguard.engine.check.SimCheck;

/**
 * BadPackets Type B (TotemGuard) — INDEPENDENT check.
 *
 * Trigger: the client's brand string advertises an autototem mod. The capture
 * layer flags {@code flagBadBrand} from the {@code minecraft:brand} plugin
 * message. Zero false positives — the client identifies itself.
 */
public final class BadPacketsBrand extends SimCheck {

    public BadPacketsBrand(KoalaGuard plugin) {
        super(plugin, "badpacketsbrand", CheckCategory.COMBAT,
                "Client brand advertises autototem");
    }

    @Override
    public void onTick(CheckContext ctx) {
        // CONSUME the flag — otherwise it latches once and re-fires every
        // tick at min-streak=1, banning off a single brand observation.
        if (ctx.data.flagBadBrand) {
            ctx.data.flagBadBrand = false;
            diverge(ctx, cfgD("score", 12.0), cfgD("threshold", 9.0),
                    cfgI("min-streak", 1),
                    "client brand advertises autototem: " + ctx.data.packetBrand,
                    false);
        }
    }
}
