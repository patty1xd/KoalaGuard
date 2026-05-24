package com.koalaguard.engine.checks.player;

import com.koalaguard.KoalaGuard;
import com.koalaguard.check.CheckCategory;
import com.koalaguard.engine.check.CheckContext;
import com.koalaguard.engine.check.SimCheck;
import com.koalaguard.engine.packet.CapturedPacket;
import com.koalaguard.engine.packet.PacketKind;
import org.bukkit.Material;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MultiTask / MultiActions. Vanilla forbids acting while a CONTINUOUS-use item
 * is in use: starting an attack first CANCELS eating / shield-blocking /
 * bow-drawing (the client sends RELEASE_USE_ITEM before the attack). A
 * MultiTask cheat keeps the use going while it attacks.
 *
 * Why the old check never fired: it rebuilt the use "session" by scanning a
 * 128-entry packet ring every tick. During a held use the ring scrolls past
 * the originating USE_ITEM long before the cheat's attacks land, so the
 * session was never considered open and the attacks were never counted.
 *
 * This version uses the netty-maintained session flag instead:
 *   • {@code usingItem} is set on USE_ITEM and cleared on RELEASE_USE_ITEM by
 *     the capture layer — so if it is still true there has been NO release
 *     since the use began (vanilla-impossible to attack here),
 *   • {@code usingItemSinceNanos} marks the session start,
 * and only credits the session when a genuine continuous-use item is actually
 * in hand (so an instantaneous right-click — pearl/snowball/bucket, which also
 * emits USE_ITEM but no release — can never produce a false positive).
 *
 * Requiring ≥2 attacks inside ONE uninterrupted continuous-use session makes
 * it vanilla-impossible → near-zero false positives. Works while standing
 * perfectly still (no frame / tick dependency).
 */
public final class MultiTaskCheck extends SimCheck {

    private static final class S { long reportedSeq = -1; }

    private final Map<UUID, S> state = new ConcurrentHashMap<>();

    public MultiTaskCheck(KoalaGuard plugin) {
        super(plugin, "multitask", CheckCategory.PLAYER, "Acting while using an item");
    }

    private static boolean continuousUse(Material m) {
        if (m == null) return false;
        if (m.isEdible()) return true;                 // all foods + honey bottle
        return switch (m) {
            case BOW, CROSSBOW, SHIELD, TRIDENT, SPYGLASS, GOAT_HORN, BRUSH,
                 POTION, MILK_BUCKET -> true;
            default -> false;
        };
    }

    @Override
    public void onTick(CheckContext ctx) {
        if (ctx.unstableBasic()) return;
        UUID id = ctx.data.getUuid();
        S s = state.computeIfAbsent(id, k -> new S());

        // Session still open (no RELEASE since the use began) AND a real
        // continuous-use item is actually in a hand AND we can see a recent
        // USE_ITEM packet in the log. The "recent USE_ITEM" gate kills the FP
        // the user reported: a stale latched usingItem flag (lost
        // RELEASE_USE_ITEM packet → flag never cleared → every later attack
        // looks like multitask). If the flag is latched but no USE_ITEM has
        // arrived in the last freshness window, we're dealing with a stale
        // flag, not a real session — skip.
        boolean open = ctx.state.usingItem
                && (continuousUse(ctx.state.inv.mainHand)
                 || continuousUse(ctx.state.inv.offHand));
        if (!open) { clean(ctx, 0.5); return; }

        long start = ctx.state.usingItemSinceNanos;
        long staleNs = cfgL("use-stale-ms", 30_000L) * 1_000_000L;
        long freshNs = cfgL("use-fresh-ms", 4_000L) * 1_000_000L;
        long nowNs = System.nanoTime();
        boolean recentUse = false;
        for (CapturedPacket p : ctx.state.log.recent(128)) {
            if (p.kind == PacketKind.USE_ITEM && nowNs - p.recvNanos <= freshNs) {
                recentUse = true; break;
            }
        }
        if (!recentUse) { clean(ctx, 0.5); return; }    // stale latched flag — skip

        int attacks = 0, placements = 0, drops = 0, swaps = 0;
        long maxActionSeq = -1;
        for (CapturedPacket p : ctx.state.log.recent(256)) {
            if (p.recvNanos < start) continue;                 // before this use
            if (p.recvNanos - start > staleNs) continue;        // safety bound

            if (p.kind == PacketKind.INTERACT_ENTITY
                    && String.valueOf(p.objA).contains("ATTACK")) {
                attacks++;
                if (p.seq > maxActionSeq) maxActionSeq = p.seq;
            } else if (p.kind == PacketKind.BLOCK_PLACE) {
                placements++;
                if (p.seq > maxActionSeq) maxActionSeq = p.seq;
            } else if (p.kind == PacketKind.DIGGING && p.strA != null
                    && (p.strA.equals("DROP_ITEM") || p.strA.equals("DROP_ALL_ITEMS"))) {
                drops++;
                if (p.seq > maxActionSeq) maxActionSeq = p.seq;
            } else if (p.kind == PacketKind.DIGGING && p.strA != null
                    && p.strA.equals("SWAP_ITEM_WITH_OFFHAND")) {
                // Vanilla cancels USE_ITEM the instant F is pressed (offhand
                // swap aborts the continuous-use animation). Seeing the swap
                // packet while the session flag is still open is a synthetic
                // chain — the cheat suppressed the RELEASE_USE_ITEM that
                // vanilla would have sent.
                swaps++;
                if (p.seq > maxActionSeq) maxActionSeq = p.seq;
            }
        }

        // Vanilla: starting ANY of these actions cancels a continuous-use item
        // (the client sends RELEASE_USE_ITEM first). The use-session flag
        // therefore can ONLY be open while none have happened. Any single
        // action mid-session is already vanilla-impossible; threshold is
        // generous to avoid sub-tick race windows.
        int totalActions = attacks + placements + drops + swaps;
        int max = cfgI("max-actions-in-use", 2);
        if (totalActions >= max && maxActionSeq > s.reportedSeq) {
            s.reportedSeq = maxActionSeq;
            diverge(ctx, cfgD("score", 6.0), cfgD("threshold", 9.0),
                    cfgI("min-streak", 2),
                    "actions during uninterrupted item-use: "
                            + attacks + " attack, " + placements + " place, "
                            + drops + " drop, " + swaps + " hand-swap",
                    false);
        }
    }

    @Override
    public void cleanup(UUID uuid) {
        super.cleanup(uuid);
        state.remove(uuid);
    }
}
