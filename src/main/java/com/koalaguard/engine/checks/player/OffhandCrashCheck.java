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

        List<CapturedPacket> recent = ctx.state.log.recent(96);
        Map<Long, Integer> perTick = new HashMap<>();
        int worst = 0;
        long worstTick = -1;
        for (CapturedPacket p : recent) {
            if (p.kind != PacketKind.DIGGING || !"SWAP_ITEM_WITH_OFFHAND".equals(p.strA)) continue;
            int c = perTick.merge(p.tickIndex, 1, Integer::sum);
            if (c > worst) { worst = c; worstTick = p.tickIndex; }
        }

        if (worst >= limit) {
            diverge(ctx, (worst - limit + 1) * cfgD("score-scale", 5.0),
                    cfgD("threshold", 9.0), cfgI("min-streak", 2),
                    worst + " offhand swaps in a single tick (#" + worstTick + ")", false);
        } else {
            clean(ctx, 1.0);
        }
    }
}
