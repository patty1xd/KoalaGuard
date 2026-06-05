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

        // Draining-tail grace: when the client sends RELEASE_USE_ITEM the
        // server may still process the action packet a tick or two later
        // because packets can arrive bunched. Per research, the vanilla
        // packet ORDER between RELEASE_USE_ITEM and a following Q-drop /
        // F-swap is NOT guaranteed — the client sends them in input order,
        // they arrive bunched, the server processes in receive order, and
        // the netty usingItem flag may briefly still be true. That race
        // produced the user-reported FPs. If a RELEASE was seen recently,
        // treat the rest of this tick as a closing use-session and skip.
        long releaseGraceNs = cfgL("release-grace-ms", 400L) * 1_000_000L;
        for (CapturedPacket p : ctx.state.log.recent(64)) {
            if (p.kind == PacketKind.DIGGING && "RELEASE_USE_ITEM".equals(p.strA)
                    && nowNs - p.recvNanos <= releaseGraceNs) {
                return;                                  // session is closing
            }
        }

        // Only count the ACTUAL cheat-defining actions: attacks and block-
        // place. Drop-item (Q) and swap-hand (F) are race-prone per research
        // — vanilla client may legitimately queue them in the same tick batch
        // as the RELEASE that cancels the use. Counting them produced the
        // user-reported FPs without catching any unique cheat behaviour the
        // attack/place pair doesn't already cover.
        int attacks = 0, placements = 0;
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
            }
        }

        // Vanilla: starting an attack or block-place cancels a continuous-use
        // item (the client sends RELEASE_USE_ITEM first). The use-session
        // flag staying open through ≥3 attacks/places IS the cheat signature
        // — a single race-window action no longer flags.
        int totalActions = attacks + placements;
        int max = cfgI("max-actions-in-use", 4);
        if (totalActions >= max && maxActionSeq > s.reportedSeq) {
            s.reportedSeq = maxActionSeq;
            diverge(ctx, cfgD("score", 6.0), cfgD("threshold", 9.0),
                    cfgI("min-streak", 3),
                    "actions during uninterrupted item-use: "
                            + attacks + " attack, " + placements + " place",
                    false);
            return;
        }

        // Sneak-toggle race during use: ≥2 START_SNEAKING + STOP_SNEAKING
        // pairs inside one use session (no release). LiquidBounce NoSlow
        // toggles sneak mid-draw to cancel use-slowdown while keeping the
        // server-side use timer running.
        int sneakToggles = 0;
        long maxToggleSeq = -1;
        for (CapturedPacket p : ctx.state.log.recent(256)) {
            if (p.recvNanos < start) continue;
            if (p.recvNanos - start > staleNs) continue;
            if (p.kind == PacketKind.ENTITY_ACTION
                    && ("START_SNEAKING".equals(p.strA) || "STOP_SNEAKING".equals(p.strA))) {
                sneakToggles++;
                if (p.seq > maxToggleSeq) maxToggleSeq = p.seq;
            }
        }
        // 6 toggles: sneak does NOT cancel item-use in vanilla, so a player
        // legitimately sneak-eating at a ledge can tap sneak a few times. Only
        // a clearly inhuman toggle count in one uninterrupted use session
        // (NoSlow sneak-race) flags.
        int maxSneak = cfgI("max-sneak-toggles-in-use", 10);
        if (sneakToggles >= maxSneak && maxToggleSeq > s.reportedSeq) {
            s.reportedSeq = maxToggleSeq;
            diverge(ctx, cfgD("sneak-score", 5.0), cfgD("threshold", 9.0),
                    cfgI("sneak-min-streak", 3),
                    "sneak-toggle race during item-use: " + sneakToggles + " toggles",
                    false);
        }
    }

    @Override
    public void cleanup(UUID uuid) {
        super.cleanup(uuid);
        state.remove(uuid);
    }
}
