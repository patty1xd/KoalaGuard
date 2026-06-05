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
        // Raised default 3→5 to allow vanilla dual-wield (main + offhand
        // placement) plus a tap of fast click-place against a wall. Vanilla
        // 1.9+ has allowed simultaneous mainhand+offhand placements; the old
        // limit of 3 was reachable by legit play.
        int limit = cfgI("max-places-per-tick", 5);

        List<CapturedPacket> recent = ctx.state.log.recent(96);
        long newest = -1;
        for (CapturedPacket p : recent) {
            if (p.kind == PacketKind.BLOCK_PLACE) { newest = p.recvNanos; break; }
        }
        if (newest < 0) { clean(ctx, 0.5); return; }

        // Count DISTINCT block coordinates placed, not raw packets, so a
        // double-fire on the same block (use-item-on retry) doesn't pad the
        // count.
        java.util.HashSet<Long> coords = new java.util.HashSet<>();
        int packets = 0;
        for (CapturedPacket p : recent) {
            if (p.kind == PacketKind.BLOCK_PLACE && newest - p.recvNanos <= windowNs) {
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
                    distinct + " distinct blocks placed within one tick ("
                            + packets + " packets)", false);
            return;
        }

        // Tick-split bypass: places spread across 2-3 ticks (4+4 or 3+3+3)
        // that each stay under the per-tick limit. Use a 2-tick (~140ms)
        // window with a smaller cumulative threshold.
        long longWindowNs = cfgL("long-window-ns", 200_000_000L);
        int longLimit = cfgI("long-window-places", 7);
        java.util.HashSet<Long> longCoords = new java.util.HashSet<>();
        for (CapturedPacket p : recent) {
            if (p.kind == PacketKind.BLOCK_PLACE && newest - p.recvNanos <= longWindowNs
                    && p.hasPos) {
                longCoords.add(((long) (int) p.x << 40)
                             ^ ((long) (int) p.y << 20)
                             ^ ((long) (int) p.z));
            }
        }
        if (longCoords.size() >= longLimit) {
            diverge(ctx, (longCoords.size() - longLimit + 1) * cfgD("long-score-scale", 3.0),
                    cfgD("threshold", 10.0), cfgI("long-min-streak", 2),
                    longCoords.size() + " distinct places in "
                            + (longWindowNs / 1_000_000L) + "ms (tick-split burst)", false);
            return;
        }
        clean(ctx, 1.0);
    }
}
