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
 * AutoTotem — combines Meteor-Client's exact packet fingerprint with
 * TotemGuard's behavioural statistics, on a movement-independent clock.
 *
 * Why the earlier versions never fired:
 *  • the pop was identified by the movement-tick counter, which FREEZES while
 *    the player stands still — exactly how autototem is tested — so every pop
 *    collapsed into one and the check never re-armed (FIXED: the pop is now an
 *    EntityResurrectEvent-driven sequence counter + transaction clock);
 *  • the "is there a click" gate always passed because real autototems DO
 *    click — the discriminator is HOW they click.
 *
 * Signals (each independently FP-safe; only a sustained score confirms):
 *  S0  Meteor fingerprint — a CLICK_WINDOW on window 0 / slot 45 (off-hand)
 *      since the pop while the player is also attacking. Vanilla cannot send
 *      an inventory click for the player screen while attacking (the GUI is
 *      not open) → impossible sequence.
 *  S0b Clustered clicks — ≥2 CLICK_WINDOW packets within one tick (Meteor
 *      sends pickup+place [+return] in the same tick). Humans cannot.
 *  S1  BadPacketsC — two consecutive HELD_ITEM_CHANGE to the same slot.
 *  S2  Consistency — re-equip interval (transaction ticks) has tiny std-dev
 *      AND low mean across many cycles (machine uniformity).
 *  S3  Fast re-equip — single interval below human reaction (supporting).
 *  S4  Brand/plugin-message advertises an autototem mod.
 *
 * Stack carry never arms (handled at the EntityResurrectEvent source), so the
 * legit "two totems / a stack" case cannot false-positive.
 */
public final class AutoTotemCheck extends SimCheck {

    private static final class S {
        boolean armed;
        long popSeq = Long.MIN_VALUE;
        long popConf;
        long popNanos;
        final Deque<Long> intervals = new ArrayDeque<>();
    }

    private final Map<UUID, S> state = new ConcurrentHashMap<>();

    public AutoTotemCheck(KoalaGuard plugin) {
        super(plugin, "autototem", CheckCategory.COMBAT,
                "Automated totem re-equip (Meteor fingerprint + behavioural)");
    }

    @Override
    public void onTick(CheckContext ctx) {
        InventoryState inv = ctx.state.inv;
        UUID id = ctx.data.getUuid();
        S s = state.computeIfAbsent(id, k -> new S());

        // S4 — advertised by brand / plugin message (independent, immediate).
        if (ctx.data.flagBadBrand) {
            diverge(ctx, cfgD("brand-score", 12.0), cfgD("threshold", 9.0), 1,
                    "client brand advertises autototem: " + ctx.data.packetBrand, false);
        }

        // ── arm on a fresh pop (sequence counter — advances even when still) ──
        if (inv.awaitingTotemTransition && inv.totemPopSeq != s.popSeq) {
            s.armed = true;
            s.popSeq = inv.totemPopSeq;
            s.popConf = inv.totemPopConf;
            s.popNanos = inv.totemPopNanos;
            return;
        }
        if (!s.armed) { clean(ctx, 0.05); return; }

        // ── re-equip edge: a totem is back in the OFF hand ──
        if (inv.offHand != Material.TOTEM_OF_UNDYING) return;   // still waiting

        s.armed = false;
        inv.awaitingTotemTransition = false;
        if (ctx.unstableBasic()) { clean(ctx, 0.5); return; }

        long interval = Math.max(0, ctx.data.confirmedTransactions - s.popConf);
        s.intervals.addLast(interval);
        while (s.intervals.size() > cfgI("sample-cap", 40)) s.intervals.removeFirst();

        // Scan the packet stream strictly since the pop (wall-clock-nanos
        // bound — independent of the frozen movement tick).
        boolean meteorSlot = false, attacked = false, dupHeld = false;
        int clicks = 0, prevHeld = Integer.MIN_VALUE;
        long firstClickNs = 0, lastClickNs = 0;
        for (CapturedPacket p : ctx.state.log.recent(256)) {
            if (p.recvNanos < s.popNanos) continue;
            switch (p.kind) {
                case CLICK_WINDOW -> {
                    clicks++;
                    if (firstClickNs == 0) firstClickNs = p.recvNanos;
                    lastClickNs = p.recvNanos;
                    if (p.intA == 45 && p.intB == 0) meteorSlot = true;
                }
                case INTERACT_ENTITY -> {
                    if (String.valueOf(p.objA).contains("ATTACK")) attacked = true;
                }
                case HELD_ITEM -> {
                    if (p.intA == prevHeld) dupHeld = true;
                    prevHeld = p.intA;
                }
                default -> { }
            }
        }
        // ≥2 inventory clicks inside ~one tick = pickup+place burst (Meteor).
        boolean clustered = clicks >= 2
                && (lastClickNs - firstClickNs) <= cfgL("cluster-window-ns", 60_000_000L);

        int fast = cfgI("fast-ticks", 4);
        boolean isFast = interval <= fast;

        double bad = 0;
        StringBuilder why = new StringBuilder();

        if (meteorSlot && (attacked || isFast)) {           // S0
            bad += cfgD("meteor-score", 9.0);
            why.append("meteor slot45/win0 click+combat ");
        }
        if (clustered) {                                     // S0b
            bad += cfgD("cluster-score", 8.0);
            why.append("clustered ").append(clicks).append(" clicks/tick ");
        }
        if (dupHeld) {                                       // S1
            bad += cfgD("badpacket-score", 8.0);
            why.append("dup-held-slot ");
        }
        if (s.intervals.size() >= cfgI("min-samples", 5)) {  // S2
            double mean = MathUtil.average(s.intervals);
            double sd = MathUtil.standardDeviation(s.intervals);
            if (sd < cfgD("max-sd-ticks", 1.2) && mean < cfgD("max-mean-ticks", 12.0)) {
                bad += cfgD("consistency-score", 9.0);
                why.append(String.format("consistent mean=%.1ft sd=%.2f n=%d ",
                        mean, sd, s.intervals.size()));
            }
        }
        if (isFast) {                                        // S3 (supporting)
            bad += cfgD("fast-score", 3.0);
            why.append("fast=").append(interval).append("t ");
        }

        if (bad > 0) {
            diverge(ctx, bad, cfgD("threshold", 9.0), cfgI("min-streak", 2),
                    "totem re-equip " + interval + "t :: " + why.toString().trim(),
                    false);
        } else {
            clean(ctx, 2.0);
        }
    }

    @Override
    public void cleanup(UUID uuid) {
        super.cleanup(uuid);
        state.remove(uuid);
    }
}
