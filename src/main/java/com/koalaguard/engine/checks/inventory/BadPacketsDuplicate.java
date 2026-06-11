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

        long maxGapMs = cfgL("max-gap-ms", 2L);
        long max = seen;
        CapturedPacket prevClick = null;
        CapturedPacket prevAttack = null;
        boolean flagged = false;

        // FP hardening (recv-time alone is NOT a reliable dedup signal):
        // a TCP/lag flush delivers a whole burst of LEGIT packets back-to-back,
        // collapsing their recv gaps to ~0 ms — a double-click inventory gather
        // or two fast butterfly clicks then read as "duplicates". The
        // discriminator is SEQ ADJACENCY: during a lag flush the client's
        // queued movement packets (one per held-back tick) interleave between
        // the two clicks/attacks, so they are NOT adjacent in capture order.
        // A duplicate-sending autototem emits its copy immediately, with
        // nothing in between. Require prev.seq + 1 == p.seq for both rules.
        for (CapturedPacket p : chrono) {
            if (p.kind == PacketKind.CLICK_WINDOW) {
                if (p.seq > max) max = p.seq;
                if (p.seq > seen && prevClick != null
                        && prevClick.seq + 1 == p.seq
                        && prevClick.intA == p.intA && prevClick.intB == p.intB
                        // Same click TYPE — a vanilla double-click gather sends
                        // two same-slot clicks with DIFFERENT types (PICKUP →
                        // DOUBLE_CLICK); only an identical-type pair is a resend.
                        && java.util.Objects.equals(prevClick.strA, p.strA)
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
            } else if (p.kind == PacketKind.INTERACT_ENTITY
                    && String.valueOf(p.objA).contains("ATTACK")) {
                // Duplicate-attack. NOTE: "two ATTACKs in one tick is
                // impossible by hand" was FALSE — tickIndex is the
                // RECONSTRUCTED movement tick, and a 20+ CPS butterfly
                // clicker genuinely lands two clicks inside one 50 ms tick.
                // Only the literal duplicate (adjacent seq, ≤3 ms apart, same
                // entity) is machine-conclusive; require two confirmations.
                if (p.seq > max) max = p.seq;
                if (p.seq > seen && prevAttack != null
                        && prevAttack.seq + 1 == p.seq
                        && prevAttack.intA == p.intA
                        && prevAttack.tickIndex == p.tickIndex
                        && (p.recvNanos - prevAttack.recvNanos) / 1_000_000L
                                <= cfgL("attack-dup-gap-ms", 3L)
                        && p.recvNanos >= prevAttack.recvNanos) {
                    flagged = true;
                    diverge(ctx, cfgD("attack-dup-score", 6.0), cfgD("threshold", 6.0),
                            cfgI("attack-dup-min-streak", 2),
                            String.format("duplicate ATTACK on entity %d in tick %d",
                                    p.intA, p.tickIndex),
                            false);
                }
                prevAttack = p;
            }
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
