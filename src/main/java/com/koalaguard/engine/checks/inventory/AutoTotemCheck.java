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
 * AutoTotem — reworked along TotemGuard's proven methodology, but expressed in
 * this engine's server-authoritative terms (no wall-clock ms, no ping
 * subtraction; the re-equip interval is measured in the confirmed-transaction
 * tick clock, which is itself the lag-comp model).
 *
 * Why the old "is there a swap/click in the chain" model failed: real
 * AutoTotem mods DO send a CLICK_WINDOW (or hotbar toggle), so that gate
 * always passed. The discriminating signals are behavioural, exactly as
 * TotemGuard found:
 *
 *  S1 BadPacketsC — two CONSECUTIVE held-item-change packets to the SAME
 *     hotbar slot after a pop. Vanilla never does this; it is the fingerprint
 *     of mods toggling the slot to grab a totem. Packet-only, timing-free.
 *  S2 Consistency — across many cycles the re-equip interval has a tiny
 *     standard deviation AND low mean. A human's reaction is variable; a bot's
 *     is machine-uniform. (TotemGuard AutoTotemB/E/H.)
 *  S3 Fast re-equip — a single interval shorter than a human can notice +
 *     act. Supporting only: never bans alone, must persist/corroborate.
 *  S4 Brand / plugin-message advertises an autototem mod.
 *
 * FP guards (also from TotemGuard): only the OFF-HAND consume→re-equip arms a
 * cycle (carrying two totems / a stack never arms it — engine side); samples
 * are dropped while the transport is unstable; the consistency signal needs
 * many samples and BOTH low SD and low mean.
 */
public final class AutoTotemCheck extends SimCheck {

    private static final class S {
        boolean armed;
        long popConf;            // confirmedTransactions at the pop
        long popTick;            // engine tick the totem was consumed
        long lastConsume = Long.MIN_VALUE;
        final Deque<Long> intervals = new ArrayDeque<>();   // re-equip ticks
    }

    private final Map<UUID, S> state = new ConcurrentHashMap<>();

    public AutoTotemCheck(KoalaGuard plugin) {
        super(plugin, "autototem", CheckCategory.COMBAT,
                "Automated totem re-equip (behavioural)");
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

        // ── arm on a fresh off-hand totem consume ──
        if (inv.awaitingTotemTransition
                && inv.totemConsumedTick != s.lastConsume) {
            s.armed = true;
            s.lastConsume = inv.totemConsumedTick;
            s.popTick = inv.totemConsumedTick;
            s.popConf = ctx.data.confirmedTransactions;
            return;
        }
        if (!s.armed) { clean(ctx, 0.05); return; }

        // ── re-equip edge: a totem is back in the OFF hand ──
        if (inv.offHand != Material.TOTEM_OF_UNDYING) return;   // still waiting

        s.armed = false;
        inv.awaitingTotemTransition = false;

        // Drop the sample entirely if the transport was unstable — the tick
        // clock could be skewed and we never risk a laggy false positive.
        if (ctx.unstableBasic()) { clean(ctx, 0.5); return; }

        long interval = Math.max(0, ctx.data.confirmedTransactions - s.popConf);
        s.intervals.addLast(interval);
        while (s.intervals.size() > cfgI("sample-cap", 40)) s.intervals.removeFirst();

        double bad = 0;
        StringBuilder why = new StringBuilder();

        // S1 — BadPacketsC: consecutive identical held-slot packets post-pop.
        if (duplicateHeldSlotSince(ctx, s.popTick)) {
            bad += cfgD("badpacket-score", 8.0);
            why.append("dup-held-slot ");
        }

        // S3 — humanly impossible single reaction.
        int fast = cfgI("fast-ticks", 4);
        if (interval <= fast) {
            bad += cfgD("fast-score", 3.0);
            why.append("fast=").append(interval).append("t ");
        }

        // S2 — machine consistency across cycles (the strongest signal).
        int minSamples = cfgI("min-samples", 5);
        if (s.intervals.size() >= minSamples) {
            double mean = MathUtil.average(s.intervals);
            double sd = MathUtil.standardDeviation(s.intervals);
            if (sd < cfgD("max-sd-ticks", 1.2) && mean < cfgD("max-mean-ticks", 12.0)) {
                bad += cfgD("consistency-score", 9.0);
                why.append(String.format("consistent mean=%.1ft sd=%.2f n=%d ",
                        mean, sd, s.intervals.size()));
            }
        }

        if (bad > 0) {
            diverge(ctx, bad, cfgD("threshold", 9.0), cfgI("min-streak", 2),
                    "totem re-equip " + interval + "t :: " + why.toString().trim(),
                    false);
        } else {
            clean(ctx, 2.0);
        }
    }

    /**
     * TotemGuard BadPacketsC: a vanilla client never sends two consecutive
     * HELD_ITEM_CHANGE packets for the same slot. AutoTotem mods that toggle
     * the hotbar to fetch a totem do. Pure packet sequence — no timing.
     */
    private boolean duplicateHeldSlotSince(CheckContext ctx, long sinceTick) {
        int prevSlot = Integer.MIN_VALUE;
        List<CapturedPacket> recent = ctx.state.log.recent(96);
        // recent() is newest-first; walk it oldest-first for chronological order.
        for (int i = recent.size() - 1; i >= 0; i--) {
            CapturedPacket p = recent.get(i);
            if (p.kind != PacketKind.HELD_ITEM || p.tickIndex < sinceTick) continue;
            if (p.intA == prevSlot) return true;
            prevSlot = p.intA;
        }
        return false;
    }

    @Override
    public void cleanup(UUID uuid) {
        super.cleanup(uuid);
        state.remove(uuid);
    }
}
