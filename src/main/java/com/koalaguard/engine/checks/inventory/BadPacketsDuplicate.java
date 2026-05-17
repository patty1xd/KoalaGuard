package com.koalaguard.engine.checks.inventory;

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
 * BadPackets Type C (TotemGuard) — INDEPENDENT check.
 *
 * Trigger: the EXACT same inventory-click slot packet resent back-to-back with
 * a near-zero gap — a poorly coded autototem re-sending its slot update.
 * Vanilla never resends an identical slot click within a few milliseconds (a
 * deliberate human double-click has tens of ms between presses). Slight FP, so
 * it is buffered and capped low in config.
 */
public final class BadPacketsDuplicate extends SimCheck {

    private final Map<UUID, Long> lastSeq = new ConcurrentHashMap<>();

    public BadPacketsDuplicate(KoalaGuard plugin) {
        super(plugin, "badpacketsdup", CheckCategory.COMBAT,
                "Duplicate inventory-click packet");
    }

    @Override
    public void onTick(CheckContext ctx) {
        if (ctx.unstableBasic()) return;
        UUID id = ctx.data.getUuid();
        long seen = lastSeq.getOrDefault(id, Long.MIN_VALUE);

        List<CapturedPacket> recent = ctx.state.log.recent(128);
        List<CapturedPacket> chrono = new ArrayList<>(recent);
        java.util.Collections.reverse(chrono);

        long maxGapMs = cfgL("max-gap-ms", 5L);
        long max = seen;
        CapturedPacket prevClick = null;
        boolean flagged = false;

        for (CapturedPacket p : chrono) {
            if (p.kind != PacketKind.CLICK_WINDOW) continue;
            if (p.seq > max) max = p.seq;
            if (p.seq > seen && prevClick != null
                    && prevClick.intA == p.intA && prevClick.intB == p.intB
                    && (p.recvNanos - prevClick.recvNanos) / 1_000_000L <= maxGapMs
                    && p.recvNanos >= prevClick.recvNanos) {
                flagged = true;
                diverge(ctx, cfgD("score", 4.0), cfgD("threshold", 6.0),
                        cfgI("min-streak", 2),
                        String.format("duplicate slot click slot=%d win=%d gap=%dms",
                                p.intA, p.intB,
                                (p.recvNanos - prevClick.recvNanos) / 1_000_000L),
                        false);
            }
            prevClick = p;
        }
        lastSeq.put(id, max);
        if (!flagged && max > seen) clean(ctx, 0.5);
    }

    @Override
    public void cleanup(UUID uuid) {
        super.cleanup(uuid);
        lastSeq.remove(uuid);
    }
}
