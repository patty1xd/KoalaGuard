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
            return;
        }

        // Empty / missing brand evasion: 2025 cheat clients suppress the
        // minecraft:brand plugin message entirely to dodge brand fingerprints.
        // Vanilla ALWAYS sends "vanilla"; mod loaders send their loader name.
        // A null or empty packetBrand more than 5s after join is the signature.
        long sinceJoinMs = System.currentTimeMillis() - ctx.data.joinMs;
        if (sinceJoinMs > cfgL("brand-grace-ms", 5000L)
                && (ctx.data.packetBrand == null
                    || ctx.data.packetBrand.isEmpty()
                    || "unknown".equalsIgnoreCase(ctx.data.packetBrand))) {
            diverge(ctx, cfgD("empty-brand-score", 4.0), cfgD("threshold", 9.0),
                    cfgI("empty-brand-min-streak", 1),
                    "client never sent a brand string (evasion)",
                    false);
        }
    }
}
