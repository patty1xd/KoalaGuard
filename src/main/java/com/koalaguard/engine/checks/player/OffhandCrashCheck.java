package com.koalaguard.engine.checks.player;

import com.koalaguard.KoalaGuard;
import com.koalaguard.check.CheckCategory;
import com.koalaguard.engine.check.CheckContext;
import com.koalaguard.engine.check.SimCheck;
import com.koalaguard.engine.packet.CapturedPacket;
import com.koalaguard.engine.packet.PacketKind;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Offhand-crash / swap flood.
 *
 * The vanilla client sends AT MOST ONE {@code SWAP_ITEM_WITH_OFFHAND} per tick
 * no matter how fast you mash F (it is gated to one per client tick). So
 * spamming the key by hand can NEVER put two swaps on the same reconstructed
 * tick — only an injected packet flood (the actual crash exploit) can. We
 * therefore count swaps bound to the SAME tick index, never a wall-clock
 * window. A normal player, even hammering F, stays at one per tick → FP-safe.
 */
public final class OffhandCrashCheck extends SimCheck {

    public OffhandCrashCheck(KoalaGuard plugin) {
        super(plugin, "offhandcrash", CheckCategory.PLAYER, "Off-hand swap packet flood");
    }

    @Override
    public void onTick(CheckContext ctx) {
        int limit = cfgI("max-swaps-per-tick", 3);   // >1 already impossible; 3 = ultra-safe
        int clickLimit = cfgI("max-clicks-per-tick", 20);
        int pluginLimit = cfgI("max-plugin-msg-per-tick", 8);

        List<CapturedPacket> recent = ctx.state.log.recent(160);
        Map<Long, Integer> swapPerTick = new HashMap<>();
        Map<Long, Integer> clickPerTick = new HashMap<>();
        int worstSwap = 0, worstClick = 0;
        long worstSwapTick = -1, worstClickTick = -1;
        for (CapturedPacket p : recent) {
            if (p.kind == PacketKind.DIGGING
                    && "SWAP_ITEM_WITH_OFFHAND".equals(p.strA)) {
                int c = swapPerTick.merge(p.tickIndex, 1, Integer::sum);
                if (c > worstSwap) { worstSwap = c; worstSwapTick = p.tickIndex; }
            } else if (p.kind == PacketKind.CLICK_WINDOW) {
                int c = clickPerTick.merge(p.tickIndex, 1, Integer::sum);
                if (c > worstClick) { worstClick = c; worstClickTick = p.tickIndex; }
            }
        }

        boolean flagged = false;
        if (worstSwap >= limit) {
            diverge(ctx, (worstSwap - limit + 1) * cfgD("score-scale", 5.0),
                    cfgD("threshold", 9.0), cfgI("min-streak", 2),
                    worstSwap + " offhand swaps in a single tick (#" + worstSwapTick + ")", false);
            flagged = true;
        }
        // CLICK_WINDOW flood — Mojang container packet crasher (LiquidBounce
        // 0.31+ malformed-slot NBT). Vanilla shift-click + drag = ≤10/tick.
        if (worstClick >= clickLimit) {
            diverge(ctx, (worstClick - clickLimit + 1) * cfgD("click-score-scale", 4.0),
                    cfgD("threshold", 9.0), cfgI("click-min-streak", 2),
                    worstClick + " inventory clicks in a single tick (#" + worstClickTick + ")", false);
            flagged = true;
        }
        if (!flagged) clean(ctx, 1.0);
    }
}
