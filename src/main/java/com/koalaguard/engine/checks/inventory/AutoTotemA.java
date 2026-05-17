package com.koalaguard.engine.checks.inventory;

import com.koalaguard.KoalaGuard;
import com.koalaguard.check.CheckCategory;
import com.koalaguard.engine.check.CheckContext;
import com.koalaguard.engine.check.SimCheck;
import com.koalaguard.engine.state.TotemCycle;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * AutoTotem Type A (TotemGuard) — INDEPENDENT check.
 *
 * Trigger: an impossibly short delay between selecting a totem and placing it
 * into the off-hand slot. A human cannot open the inventory, find the totem and
 * drop it into the off hand in tens of milliseconds; an autototem does. Reads
 * the packet-precise totem cycle only; owns nothing else.
 */
public final class AutoTotemA extends SimCheck {

    private final Map<UUID, Long> lastSeq = new ConcurrentHashMap<>();

    public AutoTotemA(KoalaGuard plugin) {
        super(plugin, "autototema", CheckCategory.COMBAT, "Impossibly fast totem re-equip");
    }

    @Override
    public void onTick(CheckContext ctx) {
        if (ctx.unstableBasic()) return;
        UUID id = ctx.data.getUuid();
        long seen = lastSeq.getOrDefault(id, Long.MIN_VALUE);
        long max = seen;
        boolean flagged = false;

        for (TotemCycle c : ctx.state.inv.cycles) {
            if (c.seq <= seen) continue;
            if (c.seq > max) max = c.seq;
            double metric = c.burstSize >= 2 ? c.selectToEquipMs : c.cycleMs;
            if (metric <= cfgD("max-ms", 90.0) || c.cycleMs <= cfgD("max-cycle-ms", 110.0)) {
                if (diverge(ctx, cfgD("score", 7.0), cfgD("threshold", 9.0),
                        cfgI("min-streak", 3),
                        String.format("re-equip %.0fms (cycle %.0fms, burst=%d)",
                                metric, c.cycleMs, c.burstSize), false)) {
                    armCombatCancel(ctx);
                }
                flagged = true;
            }
        }
        lastSeq.put(id, max);
        if (!flagged && max > seen) clean(ctx, 2.0);
    }

    @Override
    public void cleanup(UUID uuid) {
        super.cleanup(uuid);
        lastSeq.remove(uuid);
    }
}
