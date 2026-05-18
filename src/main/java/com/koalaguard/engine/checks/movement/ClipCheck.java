package com.koalaguard.engine.checks.movement;

import com.koalaguard.KoalaGuard;
import com.koalaguard.check.CheckCategory;
import com.koalaguard.engine.check.CheckContext;
import com.koalaguard.engine.check.SimCheck;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Clip / phase-teleport — catches Meteor {@code .vclip} / {@code .hclip} /
 * {@code .tp} and any module that moves the player THROUGH solid blocks.
 *
 * Why these slipped through before: a big single displacement looked like a
 * legit teleport and was discarded by the engine's >8-block baseline reset
 * before any check saw it. The engine now ray-tests the move's path BEFORE
 * that reset; if it passed through solid and there was NO server-initiated
 * teleport (Paper's internal anti-move correction does not fire
 * PlayerTeleportEvent — only real pearls / commands / portals do), it stamps
 * a clip. Moving through solid with no server teleport is conclusive, so this
 * confirms immediately and rubber-bands the player back.
 */
public final class ClipCheck extends SimCheck {

    private final Map<UUID, Long> seen = new ConcurrentHashMap<>();

    public ClipCheck(KoalaGuard plugin) {
        super(plugin, "clip", CheckCategory.MOVEMENT, "Moved through solid blocks (vclip/hclip)");
    }

    @Override
    public void onTick(CheckContext ctx) {
        long seq = ctx.data.clipSeq;
        if (seq == 0) return;
        UUID id = ctx.data.getUuid();
        if (seen.getOrDefault(id, -1L) == seq) return;
        seen.put(id, seq);

        diverge(ctx, cfgD("score", 15.0), cfgD("threshold", 9.0),
                cfgI("min-streak", 1),
                "clip: " + ctx.data.clipDetail, true);   // setback = rubber-band
    }

    @Override
    public void cleanup(UUID uuid) {
        super.cleanup(uuid);
        seen.remove(uuid);
    }
}
