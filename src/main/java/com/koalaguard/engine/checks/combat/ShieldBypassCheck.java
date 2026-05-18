package com.koalaguard.engine.checks.combat;

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
 * ShieldBypass / NoShield (auto-axe). The cheat breaks the opponent's raised
 * shield by hot-swapping to an axe, hitting, and swapping straight back — every
 * engagement, with machine-consistent timing. Server-side that is a tight
 * (held-item change → attack → held-item change) triplet whose duration barely
 * varies across many attacks.
 *
 * Conservative: a human hot-bar swap around a hit is fine — only an inhumanly
 * CONSISTENT cycle over a large sample confirms (low std-dev + fast + many
 * samples + sustained streak), so normal weapon switching never trips.
 */
public final class ShieldBypassCheck extends SimCheck {

    private static final class S {
        long lastSeq = Long.MIN_VALUE;
        final Deque<Double> cycleMs = new ArrayDeque<>();
    }

    private final Map<UUID, S> state = new ConcurrentHashMap<>();

    public ShieldBypassCheck(KoalaGuard plugin) {
        super(plugin, "shieldbypass", CheckCategory.COMBAT, "Automated shield-disable swap");
    }

    @Override
    public void onTick(CheckContext ctx) {
        if (ctx.unstableBasic()) return;
        UUID id = ctx.data.getUuid();
        S s = state.computeIfAbsent(id, k -> new S());

        List<CapturedPacket> recent = ctx.state.log.recent(160);
        List<CapturedPacket> chrono = new ArrayList<>(recent);
        java.util.Collections.reverse(chrono);

        long maxSeq = s.lastSeq;
        long swapWinNs = cfgL("swap-window-ms", 220L) * 1_000_000L;

        for (int i = 0; i < chrono.size(); i++) {
            CapturedPacket atk = chrono.get(i);
            if (atk.seq > maxSeq) maxSeq = atk.seq;
            if (atk.kind != PacketKind.INTERACT_ENTITY) continue;
            if (!String.valueOf(atk.objA).contains("ATTACK")) continue;
            if (atk.seq <= s.lastSeq) continue;

            // nearest HELD_ITEM change just BEFORE the attack
            CapturedPacket before = null;
            for (int j = i - 1; j >= 0; j--) {
                CapturedPacket q = chrono.get(j);
                if (atk.recvNanos - q.recvNanos > swapWinNs) break;
                if (q.kind == PacketKind.HELD_ITEM) { before = q; break; }
            }
            if (before == null) continue;
            // nearest HELD_ITEM change just AFTER the attack (swap back)
            CapturedPacket after = null;
            for (int j = i + 1; j < chrono.size(); j++) {
                CapturedPacket q = chrono.get(j);
                if (q.recvNanos - atk.recvNanos > swapWinNs) break;
                if (q.kind == PacketKind.HELD_ITEM) { after = q; break; }
            }
            if (after == null) continue;

            double cycle = (after.recvNanos - before.recvNanos) / 1_000_000.0;
            if (cycle > 0 && cycle < 1000) {
                s.cycleMs.addLast(cycle);
                while (s.cycleMs.size() > cfgI("sample-cap", 30)) s.cycleMs.removeFirst();
            }
        }
        s.lastSeq = maxSeq;

        if (s.cycleMs.size() < cfgI("min-samples", 8)) return;
        double mean = MathUtil.average(s.cycleMs);
        double sd = MathUtil.standardDeviation(s.cycleMs);
        if (sd < cfgD("max-sd-ms", 35.0) && mean < cfgD("max-mean-ms", 320.0)) {
            if (diverge(ctx, cfgD("score", 7.0), cfgD("threshold", 10.0),
                    cfgI("min-streak", 3),
                    String.format("auto shield-disable swap mean=%.0fms sd=%.0fms n=%d",
                            mean, sd, s.cycleMs.size()), false)) {
                armCombatCancel(ctx);
            }
        } else {
            clean(ctx, 1.0);
        }
    }

    @Override
    public void cleanup(UUID uuid) {
        super.cleanup(uuid);
        state.remove(uuid);
    }
}
