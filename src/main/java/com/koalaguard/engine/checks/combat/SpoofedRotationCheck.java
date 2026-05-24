package com.koalaguard.engine.checks.combat;

import com.koalaguard.KoalaGuard;
import com.koalaguard.check.CheckCategory;
import com.koalaguard.engine.check.CheckContext;
import com.koalaguard.engine.check.SimCheck;
import com.koalaguard.engine.packet.CapturedPacket;
import com.koalaguard.engine.packet.PacketKind;
import com.koalaguard.engine.state.PositionFrame;
import com.koalaguard.engine.util.Combat;
import com.koalaguard.util.MathUtil;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.util.Vector;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Spoofed-Rotation — UNIFIED detector for any cheat that forges the rotation
 * sent to the server while the player's actual camera looks elsewhere.
 *
 * Meteor, LiquidBounce, Wurst, Sigma all share the same primitive: when the
 * client wants to act (hit a player, place a block, charge a crystal, web
 * the floor), it overrides the outgoing yaw/pitch on that action packet (and
 * usually on movement packets too so the 3rd-person body shows the lock) to
 * point EXACTLY at the action's geometric target. The same code path
 * therefore powers KillAura, Scaffold/Tower/AutoBridge, AutoCrystal,
 * AutoAnchor, AutoTrap, AutoWeb (rotation-spoofed variant), and the silent-
 * aim mode of every aura.
 *
 * Geometric checks (AimA-D / ScaffoldCheck S0 / Honeypot look-gate) all see
 * a perfectly-aimed action and pass. The ONE invariant the cheat cannot
 * hide: across many actions on a single target, the angular error from the
 * forged rotation to the ideal target geometry is ALMOST ZERO and barely
 * varies — humans always exhibit 3-8° of standard deviation from mouse
 * tremor + reaction-time lag, even on a perfect lock. Sub-1° mean with
 * sub-0.5° SD over 18+ actions is anatomically impossible.
 *
 * Sources of samples (all funnel into one bucket per actor):
 *   • INTERACT_ENTITY ATTACK  → angle to rewound victim hitbox centre
 *   • BLOCK_PLACE             → angle to clicked face centre
 *   • USE_ITEM_ON on crystal / anchor (carried as BLOCK_PLACE in PE)
 *
 * Requires BOTH low mean AND low SD so a sniper with one good shot can't
 * confirm; the cheat's mass-perfect-aim signature is the only thing that
 * crosses both bars. Combat/action-gated, alert + combat-cancel only (no
 * movement setback).
 */
public final class SpoofedRotationCheck extends SimCheck {

    private static final class S {
        long lastEventSeq = -1;
        final Deque<Double> samples = new ArrayDeque<>();
        long firstSampleNanos = Long.MIN_VALUE;
        long lastFlagNanos    = Long.MIN_VALUE;
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

        int sampleCap   = cfgI("sample-cap", 40);
        long staleNs    = cfgL("sample-stale-ns", 6_000_000_000L);
        double maxAccept = cfgD("max-sample-deg", 12.0);   // only count would-be-hits

        // Walk recent action packets chronologically, take a single angular
        // sample per (attack | block-place) — newest events end at the queue
        // tail so old samples slide off.
        java.util.List<CapturedPacket> recent = ctx.state.log.recent(192);
        java.util.List<CapturedPacket> chrono = new java.util.ArrayList<>(recent);
        java.util.Collections.reverse(chrono);

        long maxSeq = s.lastEventSeq;
        long nowNs  = System.nanoTime();
        boolean newSample = false;
        for (CapturedPacket p : chrono) {
            if (p.seq > maxSeq) maxSeq = p.seq;
            if (p.seq <= s.lastEventSeq) continue;
            Double ang = sampleAngle(ctx, p);
            if (ang == null) continue;
            if (ang > maxAccept) continue;
            if (s.samples.isEmpty()) s.firstSampleNanos = p.recvNanos;
            s.samples.addLast(ang);
            while (s.samples.size() > sampleCap) s.samples.removeFirst();
            newSample = true;
        }
        s.lastEventSeq = maxSeq;

        // Evict stale samples (player stopped acting for a long time).
        if (!s.samples.isEmpty() && nowNs - s.firstSampleNanos > staleNs) {
            s.samples.clear();
        }

        if (!newSample) { clean(ctx, 0.3); return; }
        if (nowNs - s.lastFlagNanos < cfgL("reflag-cooldown-ns", 2_000_000_000L)) return;

        int minSamples = cfgI("min-samples", 18);
        long minSpanNs = cfgL("min-span-ns", 1_500_000_000L);
        if (s.samples.size() < minSamples) { clean(ctx, 0.3); return; }
        if (nowNs - s.firstSampleNanos < minSpanNs) return;

        double mean = MathUtil.average(s.samples);
        double sd   = MathUtil.standardDeviation(s.samples);
        double maxMean = cfgD("max-mean-deg", 1.2);
        double maxSd   = cfgD("max-sd-deg",   0.6);
        if (mean < maxMean && sd < maxSd) {
            s.lastFlagNanos = nowNs;
            if (diverge(ctx, cfgD("score", 9.0), cfgD("threshold", 9.0),
                    cfgI("min-streak", 1),
                    String.format("spoofed rotation: %d actions, mean=%.2f° sd=%.2f° (human floor ≥%.1f°/%.1f°)",
                            s.samples.size(), mean, sd, maxMean, maxSd),
                    false)) {
                armCombatCancel(ctx);
            }
        } else {
            clean(ctx, 0.4);
        }
    }

    /**
     * Returns the angular error (degrees) between the packet's stamped
     * rotation and the action's geometric target, or null if this packet is
     * not an actionable one (or geometry unavailable).
     */
    private Double sampleAngle(CheckContext ctx, CapturedPacket p) {
        if (p.kind == PacketKind.INTERACT_ENTITY
                && String.valueOf(p.objA).contains("ATTACK")) {
            // Meteor's KillAura calls Rotations.getPitch(target, Target.Body)
            // by default — aims at hitbox centre. Addons can use Target.Head
            // or Target.Feet. Take the minimum of the three so any of those
            // modes hits a near-zero angular error; a human aiming "anywhere
            // on the player" produces an angular error around the centre of
            // the actually-clicked region, not at one of three fixed points.
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
            // Clicked-face centre. p has objA = the face direction (string).
            int bx = (int) p.x, by = (int) p.y, bz = (int) p.z;
            // Skip non-real targets — instantaneous USE_ITEM (pearl/bucket/
            // food right-click) is captured as BLOCK_PLACE too in PE; only
            // sample when the clicked position is a solid or known
            // crystal/anchor surface.
            Block clicked = ctx.player.getWorld().getBlockAt(bx, by, bz);
            if (clicked == null) return null;
            Material m = clicked.getType();
            boolean acceptable = m.isSolid()
                    || m == Material.RESPAWN_ANCHOR
                    || m == Material.OBSIDIAN
                    || m == Material.BEDROCK;
            if (!acceptable) return null;
            double cx = bx + 0.5, cy = by + 0.5, cz = bz + 0.5;
            // Offset to the face centre using the stamped face if present.
            String face = String.valueOf(p.objA).toUpperCase();
            if (face.contains("UP"))    cy = by + 1.0;
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
        PositionFrame f = ctx.state.frameAtOrBefore(p.tickIndex);
        if (f == null) return null;
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
