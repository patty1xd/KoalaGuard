package com.koalaguard.engine.checks.inventory;

import com.koalaguard.KoalaGuard;
import com.koalaguard.check.CheckCategory;
import com.koalaguard.engine.check.CheckContext;
import com.koalaguard.engine.check.SimCheck;
import com.koalaguard.engine.packet.CapturedPacket;
import com.koalaguard.engine.packet.PacketKind;
import com.koalaguard.util.MathUtil;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * AutoTotem — PURELY packet-driven, pop-independent.
 *
 * Why every prior version failed: it gated on the totem pop. When autototem is
 * tested by taking continuous lethal damage, a pop happens almost every tick,
 * so the "arm on new pop" step kept clobbering the cycle before it could be
 * evaluated — it never flagged. This version ignores the pop entirely and
 * detects the autototem's ACTUAL action: the inventory packets it sends to put
 * a totem in the off hand. Works stationary, works under rapid pops, no clock
 * dependency (wall-clock nanos only, never ping subtraction).
 *
 * Meteor's fingerprint (from its source): a ClickSlot on window 0, slot 45
 * (off-hand), PICKUP — and it sends the pickup+place (+return) as 2-3 packets
 * in the SAME tick, every tick the condition holds. Signals:
 *
 *  S0  Clustered burst — ≥2 window-0 inventory clicks inside one tick. A human
 *      physically cannot, let alone repeatedly. (Primary, needs nothing else.)
 *  S1  Combat-concurrent move — an off-hand placement (slot-45 click or F-swap)
 *      with an entity ATTACK within ~250 ms. You cannot attack while the
 *      inventory GUI is open → impossible legit sequence.
 *  S2  Machine cadence — many off-hand placements with low-variance, short
 *      inter-arrival intervals (TotemGuard-style consistency).
 *  S3  Dup-held — two consecutive HELD_ITEM_CHANGE to the same slot.
 *  S4  Brand/plugin-message advertises an autototem mod.
 *
 * Legit re-equip cannot trip these: it is one un-clustered click sequence with
 * the GUI open (so no concurrent attack), slow and infrequent.
 */
public final class AutoTotemCheck extends SimCheck {

    private static final class S {
        long lastSeq = Long.MIN_VALUE;          // highest processed packet seq
        final Deque<Long> placeNanos = new ArrayDeque<>();   // off-hand placements
    }

    private final Map<UUID, S> state = new ConcurrentHashMap<>();

    public AutoTotemCheck(KoalaGuard plugin) {
        super(plugin, "autototem", CheckCategory.COMBAT,
                "Automated totem off-hand placement");
    }

    @Override
    public void onTick(CheckContext ctx) {
        UUID id = ctx.data.getUuid();
        S s = state.computeIfAbsent(id, k -> new S());

        // S4 — advertised (independent, immediate).
        if (ctx.data.flagBadBrand) {
            diverge(ctx, cfgD("brand-score", 12.0), cfgD("threshold", 9.0), 1,
                    "client brand advertises autototem: " + ctx.data.packetBrand, false);
        }

        // Newest-first → reverse to chronological.
        List<CapturedPacket> recent = ctx.state.log.recent(192);
        List<CapturedPacket> chrono = new ArrayList<>(recent);
        java.util.Collections.reverse(chrono);

        long clusterNs = cfgL("cluster-window-ns", 60_000_000L);
        long attackNs  = cfgL("attack-window-ns", 250_000_000L);

        long maxSeen = s.lastSeq;
        double bad = 0;
        StringBuilder why = new StringBuilder();
        int prevHeld = Integer.MIN_VALUE;

        for (int i = 0; i < chrono.size(); i++) {
            CapturedPacket p = chrono.get(i);

            if (p.kind == PacketKind.HELD_ITEM) {
                if (p.intA == prevHeld) {                       // S3
                    if (p.seq > s.lastSeq) {
                        bad += cfgD("badpacket-score", 8.0);
                        why.append("dup-held-slot ");
                    }
                }
                prevHeld = p.intA;
            }

            boolean offhandPlace =
                    (p.kind == PacketKind.CLICK_WINDOW && p.intB == 0 && p.intA == 45)
                 || (p.kind == PacketKind.DIGGING
                        && "SWAP_ITEM_WITH_OFFHAND".equals(p.strA));
            if (!offhandPlace) continue;

            if (p.seq > maxSeen) maxSeen = p.seq;
            if (p.seq <= s.lastSeq) continue;                   // already counted

            s.placeNanos.addLast(p.recvNanos);
            while (s.placeNanos.size() > cfgI("sample-cap", 30)) s.placeNanos.removeFirst();

            // S0 — clustered window-0 clicks around this placement.
            int near = 0;
            for (CapturedPacket q : chrono) {
                if (q.kind == PacketKind.CLICK_WINDOW && q.intB == 0
                        && Math.abs(q.recvNanos - p.recvNanos) <= clusterNs) near++;
            }
            if (near >= 2) {
                bad += cfgD("cluster-score", 9.0);
                why.append("clustered ").append(near).append("clk/tick ");
            }

            // S1 — an attack within the GUI-impossible window.
            for (CapturedPacket q : chrono) {
                if (q.kind == PacketKind.INTERACT_ENTITY
                        && String.valueOf(q.objA).contains("ATTACK")
                        && Math.abs(q.recvNanos - p.recvNanos) <= attackNs) {
                    bad += cfgD("combat-score", 8.0);
                    why.append("offhand-move while attacking ");
                    break;
                }
            }
        }
        s.lastSeq = maxSeen;

        // S2 — machine cadence across many placements.
        if (s.placeNanos.size() >= cfgI("min-samples", 5)) {
            List<Long> iv = new ArrayList<>();
            Long prev = null;
            for (long t : s.placeNanos) {
                if (prev != null) iv.add((t - prev) / 1_000_000L);   // ms
                prev = t;
            }
            if (iv.size() >= 4) {
                double mean = MathUtil.average(iv);
                double sd = MathUtil.standardDeviation(iv);
                if (sd < cfgD("max-sd-ms", 40.0) && mean < cfgD("max-mean-ms", 1500.0)) {
                    bad += cfgD("consistency-score", 9.0);
                    why.append(String.format("consistent mean=%.0fms sd=%.0f n=%d ",
                            mean, sd, s.placeNanos.size()));
                }
            }
        }

        if (bad > 0) {
            diverge(ctx, bad, cfgD("threshold", 9.0), cfgI("min-streak", 2),
                    "autototem :: " + why.toString().trim(), false);
        } else {
            clean(ctx, 0.5);
        }
    }

    @Override
    public void cleanup(UUID uuid) {
        super.cleanup(uuid);
        state.remove(uuid);
    }
}
