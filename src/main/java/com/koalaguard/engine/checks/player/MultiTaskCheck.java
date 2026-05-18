package com.koalaguard.engine.checks.player;

import com.koalaguard.KoalaGuard;
import com.koalaguard.check.CheckCategory;
import com.koalaguard.engine.check.CheckContext;
import com.koalaguard.engine.check.SimCheck;
import com.koalaguard.engine.packet.CapturedPacket;
import com.koalaguard.engine.packet.PacketKind;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MultiTask / MultiActions. Vanilla forbids acting while an item is in use:
 * starting an attack CANCELS eating / shield-blocking / bow-drawing. A
 * MultiTask cheat keeps the use going while it attacks, so the stream shows
 * an attack (often several) BETWEEN a USE_ITEM and its release, with the use
 * never interrupted. Requiring ≥2 attacks inside ONE uninterrupted use session
 * makes it vanilla-impossible → near-zero false positives.
 */
public final class MultiTaskCheck extends SimCheck {

    private final Map<UUID, Long> lastSeq = new ConcurrentHashMap<>();

    public MultiTaskCheck(KoalaGuard plugin) {
        super(plugin, "multitask", CheckCategory.PLAYER, "Acting while using an item");
    }

    @Override
    public void onTick(CheckContext ctx) {
        if (ctx.unstableBasic()) return;
        UUID id = ctx.data.getUuid();
        long seen = lastSeq.getOrDefault(id, Long.MIN_VALUE);

        List<CapturedPacket> recent = ctx.state.log.recent(128);
        List<CapturedPacket> chrono = new ArrayList<>(recent);
        java.util.Collections.reverse(chrono);

        long maxSeq = seen;
        boolean using = false;
        long useStartNanos = 0;
        int attacksInUse = 0;
        long offenceMaxSeq = -1;
        long staleNs = cfgL("use-stale-ms", 5000L) * 1_000_000L;

        for (CapturedPacket p : chrono) {
            if (p.seq > maxSeq) maxSeq = p.seq;

            if (p.kind == PacketKind.USE_ITEM) {
                using = true;
                useStartNanos = p.recvNanos;
                attacksInUse = 0;
                continue;
            }
            if (p.kind == PacketKind.DIGGING && "RELEASE_USE_ITEM".equals(p.strA)) {
                using = false;
                continue;
            }
            if (!using) continue;
            if (p.recvNanos - useStartNanos > staleNs) { using = false; continue; }

            if (p.kind == PacketKind.INTERACT_ENTITY
                    && String.valueOf(p.objA).contains("ATTACK")) {
                attacksInUse++;
                if (attacksInUse >= cfgI("max-attacks-in-use", 2) && p.seq > seen) {
                    offenceMaxSeq = Math.max(offenceMaxSeq, p.seq);
                }
            }
        }

        lastSeq.put(id, maxSeq);

        if (offenceMaxSeq > seen) {
            diverge(ctx, cfgD("score", 6.0), cfgD("threshold", 9.0),
                    cfgI("min-streak", 2),
                    "attacked repeatedly during an uninterrupted item-use", false);
        } else if (maxSeq > seen) {
            clean(ctx, 1.0);
        }
    }

    @Override
    public void cleanup(UUID uuid) {
        super.cleanup(uuid);
        lastSeq.remove(uuid);
    }
}
