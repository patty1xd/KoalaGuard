package com.koalaguard.engine.checks.combat;

import com.koalaguard.KoalaGuard;
import com.koalaguard.check.CheckCategory;
import com.koalaguard.engine.check.CheckContext;
import com.koalaguard.engine.check.SimCheck;
import com.koalaguard.engine.packet.CapturedPacket;
import com.koalaguard.engine.packet.PacketKind;
import com.koalaguard.engine.state.PositionFrame;
import com.koalaguard.engine.util.Combat;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.util.Vector;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Spoofed-Rotation — UNIFIED detector for any cheat that forges the rotation
 * sent to the server while the player's actual camera looks elsewhere
 * (Meteor / LiquidBounce / Wurst / Sigma silent-aim + silent-scaffold +
 * auto-crystal/anchor rotation spoof).
 *
 * Meteor's source confirms the cheat sends the EXACT computed target angle
 * with ZERO jitter on every action. The server therefore receives a series
 * of action packets whose angular error to the action's geometric target
 * is impossibly small — humans never hit sub-0.4° on a moving target hitbox
 * because the body is only ~11° wide at 3 blocks and you click anywhere
 * on it.
 *
 * Trigger model: per-action threshold (per-attack / per-place) on the
 * angular error, with a streak counter that survives a few human-noise
 * gaps. {@code perfect-streak} ultra-low-error actions in a row → flag.
 * That's the literal cheat fingerprint, and it fires after just a handful
 * of attacks — no minute-long variance window required.
 *
 * Sources:
 *   • INTERACT_ENTITY ATTACK  → angle to rewound victim hitbox (best of
 *     head / body / feet, since Meteor's KillAura supports all three
 *     target modes via Target.Head/Body/Feet)
 *   • BLOCK_PLACE             → angle to clicked face centre (catches
 *     silent scaffold / auto-crystal / auto-anchor in the same bucket)
 *
 * One mistake mid-streak (a real overshoot, a lag-comp miss) decrements
 * the streak but doesn't reset, so brief noise doesn't unfairly clear
 * the counter — only a sustained run of HUMAN-noise breaks it.
 *
 * Combat-cancel only on confirmation; no movement setback.
 */
public final class SpoofedRotationCheck extends SimCheck {

    private static final class S {
        long lastEventSeq = -1;
        int  perfectStreak;       // consecutive ultra-low-error actions
        int  totalActions;        // total actionable events (for description)
        long firstPerfectNanos = Long.MIN_VALUE;
        long lastFlagNanos     = Long.MIN_VALUE;
        double lastAngle;         // for the description string
        // Rolling window of recent angular errors for distribution analysis
        // (uniform-noise spoofers add 1-3° random jitter to defeat the
        // perfect-streak counter, producing flat error distribution).
        final java.util.ArrayDeque<Double> recentErr = new java.util.ArrayDeque<>();
    }

    private final Map<UUID, S> state = new ConcurrentHashMap<>();

    public SpoofedRotationCheck(KoalaGuard plugin) {
        super(plugin, "spoofedrotation", CheckCategory.COMBAT,
                "Server-sent rotation tracks every action target too perfectly");
    }

    @Override
    public void onTick(CheckContext ctx) {
        if (ctx.unstableBasic()) return;
        UUID id = ctx.data.getUuid();
        S s = state.computeIfAbsent(id, k -> new S());

        double perfectDeg  = cfgD("perfect-deg",   0.50);   // a hit ≤ this = "perfect"
        double tolerantDeg = cfgD("tolerant-deg",  3.50);   // a hit > this resets streak
        int    perfectNeed = cfgI("perfect-streak", 5);     // consecutive perfects → flag

        // Walk recent action packets in chronological order so the streak
        // counter advances in the right direction.
        java.util.List<CapturedPacket> recent = ctx.state.log.recent(192);
        java.util.List<CapturedPacket> chrono = new java.util.ArrayList<>(recent);
        java.util.Collections.reverse(chrono);

        long maxSeq = s.lastEventSeq;
        long nowNs  = System.nanoTime();
        boolean confirmed = false;
        for (CapturedPacket p : chrono) {
            if (p.seq > maxSeq) maxSeq = p.seq;
            if (p.seq <= s.lastEventSeq) continue;
            Double ang = sampleAngle(ctx, p);
            if (ang == null) continue;

            s.totalActions++;
            s.lastAngle = ang;
            s.recentErr.addLast(ang);
            while (s.recentErr.size() > cfgI("dist-sample-cap", 24))
                s.recentErr.removeFirst();

            // TEMP diagnostic — prints each sampled action's angular error.
            // Disable by setting `checks.spoofedrotation.log-samples: false`.
            if (plugin.getConfig().getBoolean("checks.spoofedrotation.log-samples", true)) {
                plugin.getLogger().info(String.format(
                        "[spoofedrotation] %s %s err=%.3f° streak=%d/%d (perfect≤%.2f° tolerant>%.2f°)",
                        ctx.player.getName(),
                        p.kind == PacketKind.INTERACT_ENTITY ? "ATTACK id=" + p.intA
                                : "PLACE @ " + (int) p.x + "," + (int) p.y + "," + (int) p.z,
                        ang, s.perfectStreak, perfectNeed, perfectDeg, tolerantDeg));
            }

            if (ang <= perfectDeg) {
                if (s.perfectStreak == 0) s.firstPerfectNanos = p.recvNanos;
                s.perfectStreak++;
            } else if (ang > tolerantDeg) {
                // Clearly human — break the streak.
                s.perfectStreak = 0;
            } else {
                // Middling — decay but don't reset (1 sloppy click in a real
                // cheat streak shouldn't clear the counter to zero).
                if (s.perfectStreak > 0) s.perfectStreak--;
            }

            if (s.perfectStreak >= perfectNeed
                    && nowNs - s.lastFlagNanos
                       >= cfgL("reflag-cooldown-ns", 2_000_000_000L)) {
                s.lastFlagNanos = nowNs;
                String msg = String.format(
                        "spoofed rotation: %d consecutive sub-%.2f° actions (last err=%.3f°)",
                        s.perfectStreak, perfectDeg, ang);
                if (diverge(ctx, cfgD("score", 9.0), cfgD("threshold", 9.0),
                        cfgI("min-streak", 1), msg, false)) {
                    armCombatCancel(ctx);
                }
                s.perfectStreak = 0;        // reset so the next streak rebuilds
                confirmed = true;
                break;
            }
        }
        s.lastEventSeq = maxSeq;

        // Uniform-noise spoof: cheats add 1-3° random noise so no individual
        // hit is "perfect", but the resulting distribution is FLAT inside
        // [0, X] rather than exponential decay (which is the human pattern —
        // most hits cluster at small error, with a long tail of misses).
        // Approximate KS via comparing the fraction in [0, mean] against the
        // exponential expectation (~0.63). A flat distribution gives ~0.50.
        if (!confirmed
                && s.recentErr.size() >= cfgI("dist-min-samples", 18)) {
            double sum = 0, mx = 0;
            for (double v : s.recentErr) { sum += v; if (v > mx) mx = v; }
            double mean = sum / s.recentErr.size();
            if (mean > 0.6 && mean < cfgD("dist-max-mean-deg", 2.5)
                    && mx < cfgD("dist-max-cap-deg", 3.5)) {
                int below = 0;
                for (double v : s.recentErr) if (v <= mean) below++;
                double belowFrac = (double) below / s.recentErr.size();
                if (belowFrac > cfgD("flat-min-frac", 0.42)
                        && belowFrac < cfgD("flat-max-frac", 0.58)) {
                    if (diverge(ctx, cfgD("flat-score", 6.0), cfgD("threshold", 9.0),
                            cfgI("flat-min-streak", 3),
                            String.format("uniform-noise spoof: mean=%.2f° max=%.2f° below-mean=%.0f%% n=%d",
                                    mean, mx, belowFrac * 100, s.recentErr.size()),
                            false)) {
                        armCombatCancel(ctx);
                    }
                    s.recentErr.clear();
                    return;
                }
            }
        }
        if (!confirmed) clean(ctx, 0.2);
    }

    /**
     * Angular error (degrees) between the packet's stamped rotation and the
     * action's geometric target. Returns null if the packet isn't actionable.
     */
    private Double sampleAngle(CheckContext ctx, CapturedPacket p) {
        if (p.kind == PacketKind.INTERACT_ENTITY
                && String.valueOf(p.objA).contains("ATTACK")) {
            // Best-of-{body, head, feet} hitbox-point: Meteor offers all three
            // as Target options. Real humans hit RANDOM points on the box; the
            // cheat hits one of these exact points every time — pick whichever
            // the cheat was aiming at this attack by taking the minimum.
            double minX, minY, minZ, maxX, maxY, maxZ;
            double[] box = ctx.state.targets.boxAt(p.intA, p.recvNanos);
            if (box != null) {
                minX = box[0]; minY = box[1]; minZ = box[2];
                maxX = box[3]; maxY = box[4]; maxZ = box[5];
            } else {
                org.bukkit.entity.Entity e = Combat.resolveById(ctx.player, p.intA, 8.0);
                if (!(e instanceof org.bukkit.entity.LivingEntity)) return null;
                var b = e.getBoundingBox();
                minX = b.getMinX(); minY = b.getMinY(); minZ = b.getMinZ();
                maxX = b.getMaxX(); maxY = b.getMaxY(); maxZ = b.getMaxZ();
            }
            double cx = (minX + maxX) * 0.5;
            double cz = (minZ + maxZ) * 0.5;
            Double aBody = angleTo(ctx, p, cx, (minY + maxY) * 0.5, cz);
            Double aHead = angleTo(ctx, p, cx, maxY - 0.10, cz);
            Double aFeet = angleTo(ctx, p, cx, minY + 0.10, cz);
            Double best = null;
            if (aBody != null) best = aBody;
            if (aHead != null && (best == null || aHead < best)) best = aHead;
            if (aFeet != null && (best == null || aFeet < best)) best = aFeet;
            return best;
        }
        if (p.kind == PacketKind.BLOCK_PLACE && p.hasPos) {
            int bx = (int) p.x, by = (int) p.y, bz = (int) p.z;
            Block clicked = ctx.player.getWorld().getBlockAt(bx, by, bz);
            if (clicked == null) return null;
            Material m = clicked.getType();
            boolean acceptable = m.isSolid()
                    || m == Material.RESPAWN_ANCHOR
                    || m == Material.OBSIDIAN
                    || m == Material.BEDROCK;
            if (!acceptable) return null;
            double cx = bx + 0.5, cy = by + 0.5, cz = bz + 0.5;
            String face = String.valueOf(p.objA).toUpperCase();
            if (face.contains("UP"))         cy = by + 1.0;
            else if (face.contains("DOWN"))  cy = by;
            else if (face.contains("NORTH")) cz = bz;
            else if (face.contains("SOUTH")) cz = bz + 1.0;
            else if (face.contains("WEST"))  cx = bx;
            else if (face.contains("EAST"))  cx = bx + 1.0;
            return angleTo(ctx, p, cx, cy, cz);
        }
        return null;
    }

    private Double angleTo(CheckContext ctx, CapturedPacket p,
                           double tx, double ty, double tz) {
        // f may be null (stationary attacker — no frame ever pushed); Combat
        // .eyeLook handles null and falls back to Player.getEyeLocation.
        PositionFrame f = ctx.state.frameAtOrBefore(p.tickIndex);
        double[] el = Combat.eyeLook(f, p, ctx.player);
        Vector look = Combat.lookVector((float) el[3], (float) el[4]);
        double dx = tx - el[0], dy = ty - el[1], dz = tz - el[2];
        double dlen = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (dlen < 0.5) return null;
        dx /= dlen; dy /= dlen; dz /= dlen;
        double dot = Math.max(-1.0, Math.min(1.0,
                look.getX() * dx + look.getY() * dy + look.getZ() * dz));
        return Math.toDegrees(Math.acos(dot));
    }

    @Override
    public void cleanup(UUID uuid) {
        super.cleanup(uuid);
        state.remove(uuid);
    }
}
