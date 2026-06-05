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
 * (weapon-swap → attack → weapon-swap-back) triplet whose duration barely
 * varies across many attacks.
 *
 * Fixes vs the old version:
 *  • A "weapon swap" is no longer only a hotbar HELD_ITEM change. Modern
 *    auto-axe almost always uses the OFF-HAND swap (F /
 *    {@code SWAP_ITEM_WITH_OFFHAND}, captured as DIGGING) or an inventory
 *    move ({@code CLICK_WINDOW}) — the old check only looked at HELD_ITEM and
 *    so never saw the real cheat. All three are accepted now.
 *  • The de-dup watermark advanced past every packet (incl. movement) each
 *    tick, so a swap→hit→swap-back triplet that spanned a tick boundary was
 *    discarded before the swap-back arrived. We now key strictly off the
 *    ATTACK packet's seq and only advance it once a full cycle is built, so a
 *    cross-tick triplet is still assembled.
 *
 * Conservative: a human swap around a hit is fine — only an inhumanly
 * CONSISTENT cycle over a large sample confirms (low std-dev + fast + many
 * samples + sustained streak), so normal weapon switching never trips.
 */
public final class ShieldBypassCheck extends SimCheck {

    private static final class S {
        long lastCycleAtkSeq = Long.MIN_VALUE;
        long lastBackstabNanos = Long.MIN_VALUE;
        final Deque<Double> cycleMs = new ArrayDeque<>();
    }

    private final Map<UUID, S> state = new ConcurrentHashMap<>();

    public ShieldBypassCheck(KoalaGuard plugin) {
        super(plugin, "shieldbypass", CheckCategory.COMBAT, "Automated shield-disable swap");
    }

    /**
     * Any packet that swaps the active weapon item. Note: HELD_ITEM
     * (hotbar number-key) and SWAP_ITEM_WITH_OFFHAND (F) are unambiguous —
     * a player pressed a swap key. CLICK_WINDOW was previously included but
     * matched EVERY inventory click — mid-fight inventory use (armor swap,
     * hotbar drag) produced spurious swap events that paired with attacks
     * into a fake "machine cadence" pattern. Restrict to hotbar swaps only.
     *
     * 1.21.11 addition: an off-hand-only CLICK_WINDOW targeting slot 45
     * (the off-hand inventory slot) is the new auto-axe vector — the cheat
     * swaps an axe into the off-hand silently and attacks with it without a
     * hotbar key press. Off-hand slot clicks ARE distinguishable from the
     * armor/hotbar-drag FPs the previous CLICK_WINDOW heuristic suffered.
     */
    private static boolean weaponSwap(CapturedPacket p) {
        return p.kind == PacketKind.HELD_ITEM
            || (p.kind == PacketKind.DIGGING
                && "SWAP_ITEM_WITH_OFFHAND".equals(p.strA))
            || (p.kind == PacketKind.CLICK_WINDOW
                && p.intA == 45 && p.intB == 0);
    }

    @Override
    public void onTick(CheckContext ctx) {
        if (ctx.unstableBasic()) return;
        UUID id = ctx.data.getUuid();
        S s = state.computeIfAbsent(id, k -> new S());

        // ── Variant 2: BACKSTAB-TELEPORT (Meteor displacement-aura family).
        // The HIGHEST-priority damage handler already cancelled the bypass
        // damage; we just need to report it through this check so staff see
        // it under the right name. A single confirmed backstab is conclusive
        // (the geometry test is exact, zero FP), so flag immediately.
        long bsNanos = ctx.state.combat.lastBackstabBlockedNanos;
        if (bsNanos > 0 && bsNanos != s.lastBackstabNanos) {
            s.lastBackstabNanos = bsNanos;
            if (diverge(ctx, cfgD("backstab-score", 12.0), cfgD("threshold", 10.0),
                    cfgI("backstab-min-streak", 1),
                    "backstab-teleport shield bypass :: " + ctx.state.combat.lastBackstabDetail,
                    false)) {
                armCombatCancel(ctx);
            }
        }

        List<CapturedPacket> recent = ctx.state.log.recent(160);
        List<CapturedPacket> chrono = new ArrayList<>(recent);
        java.util.Collections.reverse(chrono);            // oldest → newest

        long swapWinNs = cfgL("swap-window-ms", 220L) * 1_000_000L;
        long newWatermark = s.lastCycleAtkSeq;

        for (int i = 0; i < chrono.size(); i++) {
            CapturedPacket atk = chrono.get(i);
            if (atk.kind != PacketKind.INTERACT_ENTITY) continue;
            if (!String.valueOf(atk.objA).contains("ATTACK")) continue;
            if (atk.seq <= s.lastCycleAtkSeq) continue;   // already turned into a cycle

            // nearest weapon swap just BEFORE the attack
            CapturedPacket before = null;
            for (int j = i - 1; j >= 0; j--) {
                CapturedPacket q = chrono.get(j);
                if (atk.recvNanos - q.recvNanos > swapWinNs) break;
                if (weaponSwap(q)) { before = q; break; }
            }
            if (before == null) continue;
            // nearest weapon swap just AFTER the attack (swap back)
            CapturedPacket after = null;
            for (int j = i + 1; j < chrono.size(); j++) {
                CapturedPacket q = chrono.get(j);
                if (q.recvNanos - atk.recvNanos > swapWinNs) break;
                if (weaponSwap(q)) { after = q; break; }
            }
            if (after == null) continue;                  // swap-back not seen yet

            double cycle = (after.recvNanos - before.recvNanos) / 1_000_000.0;
            if (cycle > 0 && cycle < 1000) {
                s.cycleMs.addLast(cycle);
                while (s.cycleMs.size() > cfgI("sample-cap", 30)) s.cycleMs.removeFirst();
            }
            if (atk.seq > newWatermark) newWatermark = atk.seq;
        }
        s.lastCycleAtkSeq = newWatermark;

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
