package com.koalaguard.engine.checks.world;

import com.koalaguard.KoalaGuard;
import com.koalaguard.check.CheckCategory;
import com.koalaguard.engine.check.CheckContext;
import com.koalaguard.engine.check.SimCheck;
import com.koalaguard.engine.packet.CapturedPacket;
import com.koalaguard.engine.packet.PacketKind;

import java.util.List;

/**
 * Nuker. A player can only dig ONE block at a time. Nuker emits many
 * block-break packets in the same tick. We flag ≥N distinct digging packets
 * inside ~one tick — impossible by hand, and creative/spectator never reach
 * this check (the engine skips them), so legit instamining (still one block
 * at a time, sequentially) cannot false-positive.
 */
public final class NukerCheck extends SimCheck {

    public NukerCheck(KoalaGuard plugin) {
        super(plugin, "nuker", CheckCategory.WORLD, "Breaking many blocks at once");
    }

    @Override
    public void onTick(CheckContext ctx) {
        if (ctx.unstableBasic()) return;
        long windowNs = cfgL("window-ns", 70_000_000L);   // ~one tick
        int limit = cfgI("max-digs-per-tick", 4);

        List<CapturedPacket> recent = ctx.state.log.recent(128);
        long newest = -1;
        for (CapturedPacket p : recent) {
            if (p.kind == PacketKind.DIGGING
                    && ("START_DIGGING".equals(p.strA) || "FINISHED_DIGGING".equals(p.strA))) {
                newest = p.recvNanos;
                break;                       // recent() is newest-first
            }
        }
        if (newest < 0) { clean(ctx, 0.5); return; }

        // Count DISTINCT block coordinates broken in the window. A legitimate
        // Efficiency-V + Haste-II insta-mine emits START_DIGGING +
        // FINISHED_DIGGING for the SAME block back-to-back (2 packets/block),
        // so 4 instabroken blocks = 8 packets — the old packet-count limit
        // FP'd on that. Counting distinct (x,y,z) preserves the "many blocks
        // in one tick" semantic without flagging fast sequential mining.
        java.util.HashSet<Long> coords = new java.util.HashSet<>();
        int packets = 0;
        for (CapturedPacket p : recent) {
            if (p.kind == PacketKind.DIGGING
                    && ("START_DIGGING".equals(p.strA) || "FINISHED_DIGGING".equals(p.strA))
                    && newest - p.recvNanos <= windowNs) {
                packets++;
                if (p.hasPos) {
                    coords.add(((long) (int) p.x << 40)
                             ^ ((long) (int) p.y << 20)
                             ^ ((long) (int) p.z));
                }
            }
        }
        int distinct = coords.isEmpty() ? packets : coords.size();

        if (distinct >= limit) {
            diverge(ctx, (distinct - limit + 1) * cfgD("score-scale", 4.0),
                    cfgD("threshold", 10.0), cfgI("min-streak", 2),
                    distinct + " distinct blocks broken in one tick ("
                            + packets + " digging packets)", false);
        } else {
            clean(ctx, 1.0);
        }
    }
}
