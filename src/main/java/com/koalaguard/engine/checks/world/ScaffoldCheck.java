package com.koalaguard.engine.checks.world;

import com.koalaguard.KoalaGuard;
import com.koalaguard.check.CheckCategory;
import com.koalaguard.engine.check.CheckContext;
import com.koalaguard.engine.check.SimCheck;
import com.koalaguard.engine.packet.CapturedPacket;
import com.koalaguard.engine.packet.PacketKind;
import com.koalaguard.engine.util.Combat;
import org.bukkit.Location;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Scaffold / AirPlace / Tower. Vanilla REQUIRES the player's crosshair ray to
 * hit the face they place against (within reach). Scaffold places the block
 * below/behind itself while looking forward — the SENT rotation does not point
 * anywhere near the clicked block. We measure the angle between the player's
 * sent look and the block they claimed to place against; a wide angle is a
 * physically impossible placement. Generous threshold + persistence ⇒ a
 * legit fast builder (who DOES look at the block) cannot false-positive.
 */
public final class ScaffoldCheck extends SimCheck {

    private static final class S { long lastSeq = Long.MIN_VALUE; }
    private final Map<UUID, S> state = new ConcurrentHashMap<>();

    public ScaffoldCheck(KoalaGuard plugin) {
        super(plugin, "scaffold", CheckCategory.WORLD, "Placing a block while not looking at it");
    }

    @Override
    public void onTick(CheckContext ctx) {
        if (ctx.unstableBasic()) return;
        UUID id = ctx.data.getUuid();
        S s = state.computeIfAbsent(id, k -> new S());

        List<CapturedPacket> recent = ctx.state.log.recent(64);
        List<CapturedPacket> chrono = new ArrayList<>(recent);
        java.util.Collections.reverse(chrono);

        long maxSeen = s.lastSeq;
        double bad = 0;
        StringBuilder why = new StringBuilder();

        Location eye = ctx.player.getEyeLocation();
        Vector look = Combat.lookVector(ctx.state.prevYaw, ctx.state.prevPitch);

        for (CapturedPacket p : chrono) {
            if (p.kind != PacketKind.BLOCK_PLACE || !p.hasPos) continue;
            if (p.seq > maxSeen) maxSeen = p.seq;
            if (p.seq <= s.lastSeq) continue;

            double tx = p.x + 0.5 - eye.getX();
            double ty = p.y + 0.5 - eye.getY();
            double tz = p.z + 0.5 - eye.getZ();
            double dist = Math.sqrt(tx * tx + ty * ty + tz * tz);
            if (dist < 0.1) continue;
            double dot = (look.getX() * tx + look.getY() * ty + look.getZ() * tz) / dist;
            double angle = Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, dot))));

            if (angle > cfgD("max-angle", 75.0)) {
                bad += cfgD("score", 4.0);
                why.append(String.format("place @%.0f,%.0f,%.0f aim-off %.0f° ",
                        p.x, p.y, p.z, angle));
            } else if (dist > cfgD("max-reach", 6.0)) {
                bad += cfgD("score", 4.0);
                why.append(String.format("place dist %.1f ", dist));
            }
        }
        s.lastSeq = maxSeen;

        if (bad > 0) {
            diverge(ctx, bad, cfgD("threshold", 12.0), cfgI("min-streak", 4),
                    "scaffold :: " + why.toString().trim(), false);
        } else {
            clean(ctx, 0.5);
        }
    }

    @Override
    public void cleanup(UUID uuid) {
        super.cleanup(uuid);
        state.remove(uuid);
    }
}
