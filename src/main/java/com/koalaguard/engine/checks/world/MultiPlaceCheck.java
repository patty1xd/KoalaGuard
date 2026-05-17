package com.koalaguard.engine.checks.world;

import com.koalaguard.KoalaGuard;
import com.koalaguard.check.CheckCategory;
import com.koalaguard.engine.check.CheckContext;
import com.koalaguard.engine.check.SimCheck;
import com.koalaguard.engine.packet.CapturedPacket;
import com.koalaguard.engine.packet.PacketKind;

import java.util.List;

/**
 * Multi-place. A player can place at most ONE block per tick. ≥N block-place
 * packets inside a single tick is impossible by hand and is the shared
 * signature of Surround, Burrow, AutoCity, HoleFiller, Self/AutoTrap,
 * Self/AutoWeb, HighwayBuilder, LiquidFiller and burst Scaffold. Creative is
 * skipped by the engine, so legit fast building cannot false-positive.
 */
public final class MultiPlaceCheck extends SimCheck {

    public MultiPlaceCheck(KoalaGuard plugin) {
        super(plugin, "multiplace", CheckCategory.WORLD, "Placing many blocks at once");
    }

    @Override
    public void onTick(CheckContext ctx) {
        if (ctx.unstableBasic()) return;
        long windowNs = cfgL("window-ns", 70_000_000L);
        int limit = cfgI("max-places-per-tick", 3);

        List<CapturedPacket> recent = ctx.state.log.recent(96);
        long newest = -1;
        for (CapturedPacket p : recent) {
            if (p.kind == PacketKind.BLOCK_PLACE) { newest = p.recvNanos; break; }
        }
        if (newest < 0) { clean(ctx, 0.5); return; }

        int places = 0;
        for (CapturedPacket p : recent) {
            if (p.kind == PacketKind.BLOCK_PLACE && newest - p.recvNanos <= windowNs) places++;
        }
        if (places >= limit) {
            diverge(ctx, (places - limit + 1) * cfgD("score-scale", 4.0),
                    cfgD("threshold", 10.0), cfgI("min-streak", 2),
                    places + " block placements within one tick", false);
        } else {
            clean(ctx, 1.0);
        }
    }
}
