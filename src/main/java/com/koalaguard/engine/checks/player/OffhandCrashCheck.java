package com.koalaguard.engine.checks.player;

import com.koalaguard.KoalaGuard;
import com.koalaguard.check.CheckCategory;
import com.koalaguard.engine.check.CheckContext;
import com.koalaguard.engine.check.SimCheck;
import com.koalaguard.engine.packet.CapturedPacket;
import com.koalaguard.engine.packet.PacketKind;

import java.util.List;

/**
 * Offhand-crash / swap-spam. A human presses F at most a few times a second.
 * Spamming many SWAP_ITEM_WITH_OFFHAND packets per tick is an exploit/crasher.
 * Pure packet-rate within one tick — impossible by hand, FP-safe.
 */
public final class OffhandCrashCheck extends SimCheck {

    public OffhandCrashCheck(KoalaGuard plugin) {
        super(plugin, "offhandcrash", CheckCategory.PLAYER, "Off-hand swap packet spam");
    }

    @Override
    public void onTick(CheckContext ctx) {
        long windowNs = cfgL("window-ns", 80_000_000L);
        int limit = cfgI("max-swaps-per-tick", 3);

        List<CapturedPacket> recent = ctx.state.log.recent(96);
        long newest = -1;
        for (CapturedPacket p : recent) {
            if (p.kind == PacketKind.DIGGING && "SWAP_ITEM_WITH_OFFHAND".equals(p.strA)) {
                newest = p.recvNanos; break;
            }
        }
        if (newest < 0) { clean(ctx, 0.5); return; }

        int swaps = 0;
        for (CapturedPacket p : recent) {
            if (p.kind == PacketKind.DIGGING && "SWAP_ITEM_WITH_OFFHAND".equals(p.strA)
                    && newest - p.recvNanos <= windowNs) swaps++;
        }
        if (swaps >= limit) {
            diverge(ctx, (swaps - limit + 1) * cfgD("score-scale", 5.0),
                    cfgD("threshold", 9.0), cfgI("min-streak", 2),
                    swaps + " offhand swaps within one tick", false);
        } else {
            clean(ctx, 1.0);
        }
    }
}
