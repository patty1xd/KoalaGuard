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
 * AutoTotem Type F (TotemGuard) — INDEPENDENT check.
 *
 * Trigger: invalid interactions while the inventory is "open". The cycle moved
 * the totem with window-0 click packets, yet world interaction packets
 * (attack / block place / item use / look) arrived in the same span. The
 * vanilla client sends none of those while an inventory SCREEN is genuinely
 * open, so the move was synthetic. Medium FP alone — buffered, and decisive
 * when it persists.
 */
public final class AutoTotemF extends SimCheck {

    private final Map<UUID, Long> lastSeq = new ConcurrentHashMap<>();

    public AutoTotemF(KoalaGuard plugin) {
        super(plugin, "autototemf", CheckCategory.COMBAT, "Totem move while inventory open");
    }

    @Override
    public void onTick(CheckContext ctx) {
        if (ctx.unstableBasic()) return;
        UUID id = ctx.data.getUuid();
        long seen = lastSeq.getOrDefault(id, Long.MIN_VALUE);
        long max = seen;
        boolean bad = false, any = false;

        for (TotemCycle c : ctx.state.inv.cycles) {
            if (c.seq <= seen) continue;
            if (c.seq > max) max = c.seq;
            any = true;
            if (c.interactedWhileOpen) {
                bad = true;
                if (diverge(ctx, cfgD("score", 5.0), cfgD("threshold", 10.0),
                        cfgI("min-streak", 3),
                        "totem moved while interacting with the world (screen not open)",
                        false)) {
                    armCombatCancel(ctx);
                }
            }
        }
        lastSeq.put(id, max);
        if (!bad && any) clean(ctx, 1.5);
    }

    @Override
    public void cleanup(UUID uuid) {
        super.cleanup(uuid);
        lastSeq.remove(uuid);
    }
}
