package com.koalaguard.engine.checks.world;

import com.koalaguard.KoalaGuard;
import com.koalaguard.check.CheckCategory;
import com.koalaguard.engine.check.CheckContext;
import com.koalaguard.engine.check.SimCheck;
import com.koalaguard.engine.packet.CapturedPacket;
import com.koalaguard.engine.packet.PacketKind;
import com.koalaguard.engine.state.PositionFrame;
import com.koalaguard.engine.util.Combat;
import com.koalaguard.util.MathUtil;
import org.bukkit.util.Vector;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Scaffold / Bridge / Tower — REBUILT to be time-aligned.
 *
 * The old version compared the LIVE eye + LATEST rotation to the clicked
 * block's CENTRE, with a loose 75°/streak-4 gate a bot looking slightly down
 * cleared every time. Now every placement is reconstructed at the EXACT tick
 * the block-place packet was bound to, measured against the real clicked FACE,
 * which lets tight FP-safe thresholds work.
 *
 *  S0  Aim-off place — the reconstructed look does not point anywhere near the
 *      face the player claims to have placed against (vanilla requires the
 *      crosshair ray to hit that face within reach).
 *  S1  Backward bridge — the player is moving and placing the block into the
 *      half-space BEHIND the direction of travel, below foot level (walking
 *      backward/forward off an edge laying blocks under themselves).
 *  S2  Machine cadence — near-constant tick interval between placements over a
 *      large sample (automated tower/bridge), with near-constant pitch.
 *
 * A legit fast builder looks at the block, places toward travel, and has a
 * variable human cadence, so none of the signals trip.
 */
public final class ScaffoldCheck extends SimCheck {

    private static final class S {
        long lastSeq = Long.MIN_VALUE;
        long lastPlaceTick = Long.MIN_VALUE;
        final Deque<Long> intervals = new ArrayDeque<>();
        final Deque<Double> pitches = new ArrayDeque<>();
    }

    private final Map<UUID, S> state = new ConcurrentHashMap<>();

    public ScaffoldCheck(KoalaGuard plugin) {
        super(plugin, "scaffold", CheckCategory.WORLD, "Automated / aim-off block placement");
    }

    @Override
    public void onTick(CheckContext ctx) {
        if (ctx.unstableBasic()) return;
        UUID id = ctx.data.getUuid();
        S s = state.computeIfAbsent(id, k -> new S());

        List<CapturedPacket> recent = ctx.state.log.recent(96);
        List<CapturedPacket> chrono = new ArrayList<>(recent);
        java.util.Collections.reverse(chrono);

        long maxSeen = s.lastSeq;
        double bad = 0;
        StringBuilder why = new StringBuilder();

        for (CapturedPacket p : chrono) {
            if (p.kind != PacketKind.BLOCK_PLACE || !p.hasPos) continue;
            if (p.seq > maxSeen) maxSeen = p.seq;
            if (p.seq <= s.lastSeq) continue;

            // Reconstruct eye/look at the EXACT place tick.
            PositionFrame f = ctx.state.frameAtOrBefore(p.tickIndex);
            double[] el = Combat.eyeLook(f, ctx.player);
            double ex = el[0], ey = el[1], ez = el[2];
            Vector look = Combat.lookVector((float) el[3], (float) el[4]);

            // The clicked FACE point, not the block centre.
            double[] n = faceNormal(p.strA);
            double fx = p.x + 0.5 + n[0] * 0.5;
            double fy = p.y + 0.5 + n[1] * 0.5;
            double fz = p.z + 0.5 + n[2] * 0.5;

            double tx = fx - ex, ty = fy - ey, tz = fz - ez;
            double dist = Math.sqrt(tx * tx + ty * ty + tz * tz);
            if (dist < 0.1) continue;
            double dot = (look.getX() * tx + look.getY() * ty + look.getZ() * tz) / dist;
            double angle = Math.toDegrees(Math.acos(MathUtil.clampD(dot, -1, 1)));

            boolean aimOff = angle > cfgD("max-angle", 78.0);
            if (aimOff) {
                bad += cfgD("aim-score", 5.0);
                why.append(String.format("place @%.0f,%.0f,%.0f aim-off %.0f° ",
                        p.x, p.y, p.z, angle));
            } else if (dist > cfgD("max-reach", 5.2)) {
                bad += cfgD("aim-score", 5.0);
                why.append(String.format("place dist %.1f ", dist));
            }

            // S1 — backward bridge: moving, placing behind travel, below feet.
            if (f != null) {
                double mh = Math.sqrt(f.dx * f.dx + f.dz * f.dz);
                if (mh > cfgD("min-move", 0.12) && p.y <= Math.floor(ey - 1.4)) {
                    double mlen = mh;
                    double plen = Math.sqrt(tx * tx + tz * tz);
                    if (plen > 1e-4) {
                        double mdot = (f.dx * tx + f.dz * tz) / (mlen * plen);
                        if (mdot < cfgD("back-dot", -0.30)) {  // placement opposes travel
                            bad += cfgD("back-score", 5.0);
                            why.append("backward-bridge ");
                        }
                    }
                }
            }

            // Sample cadence + pitch for S2.
            if (s.lastPlaceTick != Long.MIN_VALUE) {
                long iv = p.tickIndex - s.lastPlaceTick;
                if (iv > 0 && iv < 200) {
                    s.intervals.addLast(iv);
                    while (s.intervals.size() > cfgI("sample-cap", 24)) s.intervals.removeFirst();
                }
            }
            s.lastPlaceTick = p.tickIndex;
            s.pitches.addLast((double) el[4]);
            while (s.pitches.size() > cfgI("sample-cap", 24)) s.pitches.removeFirst();
        }
        s.lastSeq = maxSeen;

        // S2 — machine cadence ONLY CORROBORATES. Humans bridge rhythmically
        // and hold a steady downward pitch, so cadence on its own false-flags
        // legit fast bridging. It now adds nothing unless an aim-off /
        // backward-bridge place was ALSO seen this pass (bad > 0).
        if (bad > 0 && s.intervals.size() >= cfgI("min-samples", 10)) {
            double sdIv = MathUtil.standardDeviation(s.intervals);
            double sdPitch = MathUtil.standardDeviation(s.pitches);
            if (sdIv < cfgD("max-sd-interval", 0.6)
                    && sdPitch < cfgD("max-sd-pitch", 0.8)) {
                bad += cfgD("cadence-score", 4.0);
                why.append(String.format("machine cadence sdIv=%.2f sdPitch=%.2f n=%d ",
                        sdIv, sdPitch, s.intervals.size()));
            }
        }

        if (bad > 0) {
            diverge(ctx, bad, cfgD("threshold", 11.0), cfgI("min-streak", 4),
                    "scaffold :: " + why.toString().trim(), false);
        } else {
            clean(ctx, 0.5);
        }
    }

    private static double[] faceNormal(String face) {
        if (face == null) return new double[]{0, 1, 0};
        return switch (face) {
            case "UP"    -> new double[]{0, 1, 0};
            case "DOWN"  -> new double[]{0, -1, 0};
            case "NORTH" -> new double[]{0, 0, -1};
            case "SOUTH" -> new double[]{0, 0, 1};
            case "WEST"  -> new double[]{-1, 0, 0};
            case "EAST"  -> new double[]{1, 0, 0};
            default      -> new double[]{0, 1, 0};
        };
    }

    @Override
    public void cleanup(UUID uuid) {
        super.cleanup(uuid);
        state.remove(uuid);
    }
}
