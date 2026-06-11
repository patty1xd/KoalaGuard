package com.koalaguard.engine.checks.player;

import com.koalaguard.KoalaGuard;
import com.koalaguard.check.CheckCategory;
import com.koalaguard.engine.check.CheckContext;
import com.koalaguard.engine.check.SimCheck;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * BadPackets S — protocol-illegal movement packets. The netty layer already
 * CANCELLED the packet (NaN/Infinity coordinates or rotation, |pitch| beyond
 * ±90, coordinates past the vanilla world limit — crash/exploit payloads and
 * derp-style rotations a vanilla client cannot emit) and stamped
 * {@code sanitySeq}; this check turns the stamp into the violation pipeline.
 *
 * No lag gate and no streak: there is no network condition under which a
 * legitimate client produces these values, so a single occurrence is
 * conclusive. The defense (the cancel) already happened on the netty thread —
 * this is the reporting/punishment half.
 */
public final class BadPacketsSanity extends SimCheck {

    private final Map<UUID, Long> seen = new ConcurrentHashMap<>();

    public BadPacketsSanity(KoalaGuard plugin) {
        super(plugin, "badpacketssanity", CheckCategory.PACKET,
                "Protocol-illegal movement packet (crash/derp payload)");
    }

    @Override
    public void onTick(CheckContext ctx) {
        long seq = ctx.data.sanitySeq;
        if (seq == 0) return;
        UUID id = ctx.data.getUuid();
        if (seen.getOrDefault(id, 0L) == seq) return;
        seen.put(id, seq);

        diverge(ctx, cfgD("score", 20.0), cfgD("threshold", 9.0),
                cfgI("min-streak", 1),
                "illegal packet: " + ctx.data.sanityDetail, false);
    }

    @Override
    public void cleanup(UUID uuid) {
        super.cleanup(uuid);
        seen.remove(uuid);
    }
}
