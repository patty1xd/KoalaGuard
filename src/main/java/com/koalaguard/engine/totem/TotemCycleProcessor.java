package com.koalaguard.engine.totem;

import com.koalaguard.data.PlayerData;
import com.koalaguard.engine.packet.CapturedPacket;
import com.koalaguard.engine.packet.PacketKind;
import com.koalaguard.engine.state.InventoryState;
import com.koalaguard.engine.state.PlayerState;
import com.koalaguard.engine.state.TotemCycle;
import org.bukkit.Material;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Reconstructs the TotemGuard "totem cycle" from the netty packet stream with
 * NANOSECOND precision — not the 50 ms server tick and not a Bukkit event
 * (synthetic packet inventory moves frequently raise no InventoryClickEvent,
 * which is exactly why the old check never detected).
 *
 * Pop  = {@code EntityResurrectEvent} (server-applied; fires while perfectly
 *        still) — already stamped into {@link InventoryState} by the listener.
 * Re-equip = the off hand holds a totem again AND ≥1 inventory-move packet
 *        (window-0 click / off-hand swap) was captured after the pop. The cycle
 *        time is measured between the captured packet nanoseconds.
 *
 * FP-proof by construction: a legit totem STACK only decrements — the off hand
 * never stops being a totem AND no inventory packet is sent ⇒ no cycle is ever
 * built. A manual re-equip opens the real screen ⇒ slow, variable, and the
 * client sends no world interaction while it is open ⇒ no statistic trips.
 */
public final class TotemCycleProcessor {

    private TotemCycleProcessor() {}

    private static final long ABANDON_NS = 8_000_000_000L;   // 8 s with no re-equip
    private static final int  CYCLE_CAP  = 40;

    public static void process(PlayerData d, PlayerState s) {
        InventoryState inv = s.inv;

        // A fresh pop opens a new cycle (strongest trigger — takes priority).
        if (inv.totemPopSeq != inv.lastProcessedPopSeq) {
            inv.lastProcessedPopSeq = inv.totemPopSeq;
            // A pop supersedes any pending vuln cycle (same physical event).
            inv.lastProcessedVulnSeq = inv.vulnSeq;
            inv.cycleActive   = true;
            inv.cyclePopNanos = inv.totemPopNanos;
            inv.cyclePopTick  = inv.totemConsumedTick;
        }

        // FIRST-totem trigger: damaged while the off hand had no totem and no
        // cycle is already running. Reconstructed into the SAME cycle model,
        // so AutoTotemA–F judge it identically. FP-proof for the same reason:
        // no inventory packet ⇒ no burst ⇒ no cycle is ever built.
        if (!inv.cycleActive && inv.vulnSeq != inv.lastProcessedVulnSeq) {
            inv.lastProcessedVulnSeq = inv.vulnSeq;
            if (System.nanoTime() - inv.vulnNanos <= ABANDON_NS) {
                inv.cycleActive   = true;
                inv.cyclePopNanos = inv.vulnNanos;
                inv.cyclePopTick  = inv.vulnTick;
            }
        }
        if (!inv.cycleActive) return;

        long now = System.nanoTime();
        if (now - inv.cyclePopNanos > ABANDON_NS) {     // legit single-totem death
            inv.cycleActive = false;
            return;
        }

        boolean totemOff = inv.offHand == Material.TOTEM_OF_UNDYING;
        if (!totemOff) return;                          // not re-equipped yet

        // Collect the post-pop inventory-move burst (chronological).
        List<CapturedPacket> burst = new ArrayList<>();
        boolean interactedWhileOpen = false;
        for (CapturedPacket p : s.log.recent(256)) {
            if (p.recvNanos < inv.cyclePopNanos) continue;
            boolean move = p.kind == PacketKind.CLICK_WINDOW
                    || (p.kind == PacketKind.DIGGING
                        && "SWAP_ITEM_WITH_OFFHAND".equals(p.strA));
            if (move) { burst.add(p); continue; }
            // A world interaction in the same span ⇒ the inventory SCREEN was
            // not genuinely open (Type F).
            if (p.kind == PacketKind.INTERACT_ENTITY
                    || p.kind == PacketKind.BLOCK_PLACE
                    || p.kind == PacketKind.USE_ITEM
                    || p.kind == PacketKind.ANIMATION
                    || (p.kind == PacketKind.MOVEMENT && p.hasRot)) {
                interactedWhileOpen = true;
            }
        }
        if (burst.isEmpty()) return;                    // stack carry → never built

        burst.sort(Comparator.comparingLong(a -> a.recvNanos));
        long firstNs = burst.get(0).recvNanos;
        long lastNs  = burst.get(burst.size() - 1).recvNanos;

        double cycleMs = Math.max(0.0, (lastNs - inv.cyclePopNanos) / 1_000_000.0);
        double selectToEquipMs = burst.size() >= 2
                ? (lastNs - firstNs) / 1_000_000.0
                : cycleMs;

        double[] gaps = new double[Math.max(0, burst.size() - 1)];
        for (int i = 1; i < burst.size(); i++) {
            gaps[i - 1] = (burst.get(i).recvNanos - burst.get(i - 1).recvNanos) / 1_000_000.0;
        }

        TotemCycle c = new TotemCycle(++inv.cycleSeq, cycleMs, selectToEquipMs,
                gaps, burst.size(), interactedWhileOpen, inv.cyclePopNanos, lastNs);
        inv.cycles.addLast(c);
        while (inv.cycles.size() > CYCLE_CAP) inv.cycles.removeFirst();

        inv.cycleActive = false;
    }
}
