package com.koalaguard.engine.checks.inventory;

import com.koalaguard.KoalaGuard;
import com.koalaguard.check.CheckCategory;
import com.koalaguard.engine.check.CheckContext;
import com.koalaguard.engine.check.SimCheck;
import com.koalaguard.engine.packet.CapturedPacket;
import com.koalaguard.engine.packet.PacketKind;
import com.koalaguard.engine.state.InventoryState;
import com.koalaguard.util.MathUtil;
import org.bukkit.Material;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * AutoTotem — REBUILT to stop depending on Bukkit {@code InventoryClickEvent}.
 *
 * The old version only ever advanced when {@code InventoryClickEvent} /
 * {@code PlayerSwapHandItemsEvent} fired for the re-equip. A packet-level
 * inventory move (window 0, screen never opened — exactly what fast autototems
 * do) frequently does NOT raise those events, so {@code reequipSeq} never moved
 * and the check {@code clean()}ed forever. That is why it never detected.
 *
 * The pop is still taken from {@code EntityResurrectEvent} (the server applies
 * it — 100% reliable, fires while perfectly still). The RE-EQUIP is now read
 * from the server-authoritative inventory mirror (off-hand becomes a totem
 * again), which is version-independent and impossible for a cheat to hide, and
 * corroborated from the raw packet stream.
 *
 *  S0  Packet-move re-equip — a window-click / off-hand-swap packet moved the
 *      totem while the player was simultaneously rotating or attacking. The
 *      vanilla client sends neither while an inventory SCREEN is open, so the
 *      screen was never open ⇒ the move was synthetic. Near-certain.
 *  S1  Instant / fast re-equip — the off hand held a totem again within a tiny
 *      number of confirmed-transaction ticks of the pop.
 *  S2  Machine consistency — re-equip interval has low variance over a sample.
 *  S3  Client brand advertises an autototem mod.
 *
 * FP-proof: a legit totem STACK in the off hand just DECREMENTS on a pop — the
 * off hand never stops being a totem, so the false→true transition never
 * happens AND no inventory-move packet is sent ⇒ nothing is ever judged. A
 * manual re-equip via the real inventory screen is slow, variable, and the
 * client sends no rotation/attack while that screen is open ⇒ no signal trips.
 */
public final class AutoTotemCheck extends SimCheck {

    private static final class S {
        long handledPopSeq = Long.MIN_VALUE;
        boolean awaiting;
        long popConfAt;
        long popTickAt;
        boolean wasTotemOff;
        final Deque<Long> intervals = new ArrayDeque<>();
    }

    private final Map<UUID, S> state = new ConcurrentHashMap<>();

    public AutoTotemCheck(KoalaGuard plugin) {
        super(plugin, "autototem", CheckCategory.COMBAT, "Automated totem re-equip");
    }

    @Override
    public void onTick(CheckContext ctx) {
        InventoryState inv = ctx.state.inv;
        UUID id = ctx.data.getUuid();
        S s = state.computeIfAbsent(id, k -> new S());

        boolean totemOff = inv.offHand == Material.TOTEM_OF_UNDYING;

        // S3 — brand (independent of any cycle).
        if (ctx.data.flagBadBrand) {
            diverge(ctx, cfgD("brand-score", 12.0), cfgD("threshold", 9.0), 1,
                    "client brand advertises autototem: " + ctx.data.packetBrand, false);
        }

        // A fresh pop began a new re-equip cycle.
        if (inv.totemPopSeq != s.handledPopSeq) {
            s.handledPopSeq = inv.totemPopSeq;
            s.awaiting = true;
            s.popConfAt = inv.totemPopConf;
            s.popTickAt = inv.totemConsumedTick;
            // If the off hand is ALREADY a totem on the same mirror tick the pop
            // fired, the consume→refill happened sub-tick (the mirror never saw
            // the dip). That is the strongest "instant" case — handled below
            // via the packet corroboration so a stack carry stays FP-safe.
        }

        boolean reequipObserved = false;
        if (s.awaiting && totemOff && !s.wasTotemOff) {
            reequipObserved = true;                       // mirror saw the dip→totem
        } else if (s.awaiting && totemOff && s.wasTotemOff
                && ctx.data.confirmedTransactions - s.popConfAt <= cfgI("instant-ticks", 1)) {
            reequipObserved = true;                       // sub-tick instant refill
        }

        // Abandon a pop whose re-equip never came (legit single-totem death).
        if (s.awaiting && ctx.data.confirmedTransactions - s.popConfAt > cfgI("await-timeout-ticks", 200)) {
            s.awaiting = false;
        }

        if (!reequipObserved) {
            s.wasTotemOff = totemOff;
            if (!s.awaiting) clean(ctx, 0.05);
            return;
        }
        s.awaiting = false;
        s.wasTotemOff = totemOff;

        long interval = Math.max(0, ctx.data.confirmedTransactions - s.popConfAt);
        s.intervals.addLast(interval);
        while (s.intervals.size() > cfgI("sample-cap", 30)) s.intervals.removeFirst();

        // Did a synthetic inventory move (click/offhand-swap) occur between the
        // pop and now WHILE the player was rotating or attacking? The vanilla
        // client sends no rotation/attack while an inventory SCREEN is open, so
        // an interleaved move proves the screen was never open.
        boolean movePacket = false;
        boolean busyMove = false;
        List<CapturedPacket> log = ctx.state.log.recent(160);
        for (CapturedPacket p : log) {
            if (p.tickIndex < s.popTickAt - 1) break;     // log is tick-ordered
            boolean isMove = (p.kind == PacketKind.CLICK_WINDOW)
                    || (p.kind == PacketKind.DIGGING
                        && "SWAP_ITEM_WITH_OFFHAND".equals(p.strA));
            if (!isMove) continue;
            movePacket = true;
            for (CapturedPacket q : log) {
                if (Math.abs(q.tickIndex - p.tickIndex) > 1) continue;
                if ((q.kind == PacketKind.MOVEMENT && q.hasRot)
                        || (q.kind == PacketKind.INTERACT_ENTITY
                            && String.valueOf(q.objA).contains("ATTACK"))) {
                    busyMove = true;
                    break;
                }
            }
            if (busyMove) break;
        }

        double bad = 0;
        StringBuilder why = new StringBuilder();

        if (busyMove) {                                                   // S0
            bad += cfgD("packet-score", 10.0);
            why.append("packet-move re-equip while moving/attacking ");
        }
        if (interval <= cfgI("fast-ticks", 3) && movePacket) {            // S1
            bad += cfgD("fast-score", 5.0);
            why.append("fast=").append(interval).append("t ");
        }
        if (s.intervals.size() >= cfgI("min-samples", 5) && movePacket) { // S2
            double mean = MathUtil.average(s.intervals);
            double sd = MathUtil.standardDeviation(s.intervals);
            if (sd < cfgD("max-sd-ticks", 1.6) && mean < cfgD("max-mean-ticks", 16.0)) {
                bad += cfgD("consistency-score", 9.0);
                why.append(String.format("consistent mean=%.1ft sd=%.2f n=%d ",
                        mean, sd, s.intervals.size()));
            }
        }

        if (debug()) {
            plugin.getLogger().info("[autototem] " + ctx.player.getName()
                    + " interval=" + interval + "t movePkt=" + movePacket
                    + " busy=" + busyMove + " n=" + s.intervals.size() + " bad=" + bad);
        }

        if (bad > 0) {
            diverge(ctx, bad, cfgD("threshold", 9.0), cfgI("min-streak", 2),
                    "autototem :: " + why.toString().trim(), false);
        } else {
            clean(ctx, 1.5);
        }
    }

    @Override
    public void cleanup(UUID uuid) {
        super.cleanup(uuid);
        state.remove(uuid);
    }
}
