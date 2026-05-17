package com.koalaguard.engine.checks.inventory;

import com.koalaguard.KoalaGuard;
import com.koalaguard.check.CheckCategory;
import com.koalaguard.engine.check.CheckContext;
import com.koalaguard.engine.check.SimCheck;
import com.koalaguard.engine.state.InventoryState;
import com.koalaguard.util.MathUtil;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * AutoTotem — driven by SERVER-side Bukkit events, not raw packet decoding.
 *
 * The server fires {@code EntityResurrectEvent} the instant a totem pops, and
 * {@code InventoryClickEvent}/{@code PlayerSwapHandItemsEvent} the instant the
 * totem is moved back to the off hand — no matter how the cheat crafted the
 * packet, and even when the player is perfectly still. {@link com.koalaguard
 * .listener.BukkitStateListener} records the pop and the re-equip; this check
 * just judges the relationship (TotemGuard methodology, on the
 * confirmed-transaction tick clock — no wall-clock ms, no ping subtraction):
 *
 *  S0  re-equip happened while the player was ATTACKING — the inventory GUI
 *      cannot be open while you swing at an entity → impossible by hand.
 *  S1  re-equip faster than a human can notice+act (few transaction ticks).
 *  S2  machine-consistent re-equip interval across many pops (low sd + mean).
 *  S3  brand / plugin-message advertises an autototem mod.
 *
 * FP-proof: a legit totem STACK in the off hand fires NEITHER inventory event
 * (the stack just decrements) → reequipSeq never advances → nothing is judged.
 * A manual re-equip fires the event but is slow, variable, and not done while
 * attacking → no signal trips.
 */
public final class AutoTotemCheck extends SimCheck {

    private static final class S {
        long lastReequipSeq = Long.MIN_VALUE;
        final Deque<Long> intervals = new ArrayDeque<>();   // transaction ticks
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

        if (ctx.data.flagBadBrand) {                                       // S3
            diverge(ctx, cfgD("brand-score", 12.0), cfgD("threshold", 9.0), 1,
                    "client brand advertises autototem: " + ctx.data.packetBrand, false);
        }

        if (inv.reequipSeq == s.lastReequipSeq) { clean(ctx, 0.05); return; }
        s.lastReequipSeq = inv.reequipSeq;                                 // new cycle

        long interval = Math.max(0, inv.reequipConf - inv.totemPopConf);
        s.intervals.addLast(interval);
        while (s.intervals.size() > cfgI("sample-cap", 30)) s.intervals.removeFirst();

        double bad = 0;
        StringBuilder why = new StringBuilder();

        if (inv.reequipBusy) {                                             // S0
            bad += cfgD("combat-score", 9.0);
            why.append("re-equip while attacking ");
        }
        if (interval <= cfgI("fast-ticks", 3)) {                           // S1
            bad += cfgD("fast-score", 4.0);
            why.append("fast=").append(interval).append("t ");
        }
        if (s.intervals.size() >= cfgI("min-samples", 5)) {                // S2
            double mean = MathUtil.average(s.intervals);
            double sd = MathUtil.standardDeviation(s.intervals);
            if (sd < cfgD("max-sd-ticks", 1.5) && mean < cfgD("max-mean-ticks", 14.0)) {
                bad += cfgD("consistency-score", 9.0);
                why.append(String.format("consistent mean=%.1ft sd=%.2f n=%d ",
                        mean, sd, s.intervals.size()));
            }
        }

        if (debug()) {
            plugin.getLogger().info("[autototem] " + ctx.player.getName()
                    + " cycle interval=" + interval + "t busy=" + inv.reequipBusy
                    + " n=" + s.intervals.size() + " bad=" + bad);
        }

        if (bad > 0) {
            diverge(ctx, bad, cfgD("threshold", 9.0), cfgI("min-streak", 2),
                    "autototem :: " + why.toString().trim(), false);
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
