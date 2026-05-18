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
        // continuous-use item is actually in a hand.
        boolean open = ctx.state.usingItem
                && (continuousUse(ctx.state.inv.mainHand)
                 || continuousUse(ctx.state.inv.offHand));
        if (!open) { clean(ctx, 0.5); return; }

        long start = ctx.state.usingItemSinceNanos;
        long staleNs = cfgL("use-stale-ms", 30_000L) * 1_000_000L;

        int attacks = 0;
        long maxAttackSeq = -1;
        for (CapturedPacket p : ctx.state.log.recent(256)) {
            if (p.kind != PacketKind.INTERACT_ENTITY) continue;
            if (!String.valueOf(p.objA).contains("ATTACK")) continue;
            if (p.recvNanos < start) continue;                 // before this use
            if (p.recvNanos - start > staleNs) continue;        // safety bound
            attacks++;
            if (p.seq > maxAttackSeq) maxAttackSeq = p.seq;
        }

        if (attacks >= cfgI("max-attacks-in-use", 2) && maxAttackSeq > s.reportedSeq) {
            s.reportedSeq = maxAttackSeq;
            diverge(ctx, cfgD("score", 6.0), cfgD("threshold", 9.0),
                    cfgI("min-streak", 2),
                    "attacked " + attacks + "x during an uninterrupted item-use",
                    false);
        }
    }

    @Override
    public void cleanup(UUID uuid) {
        super.cleanup(uuid);
        state.remove(uuid);
    }
}
