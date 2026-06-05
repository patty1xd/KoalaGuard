package com.koalaguard.engine.state;

import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.util.BoundingBox;

import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;

/**
 * Per-player victim position history — combat lag compensation.
 *
 * Without this, aim/reach checks resolve the victim at its LIVE position, which
 * is 10-25° away from where it really was when the attack packet was sent
 * (network + per-tick processing latency). That desync is exactly why the
 * geometric machine-aim signal (AimE) had to be disabled and why the angle
 * checks needed huge slack. The engine snapshots every nearby living entity's
 * hitbox once per tick with a netty-comparable nanosecond stamp; checks rewind
 * the victim to the attack packet's {@code recvNanos}.
 *
 * Main-thread only (snapshotted from the per-tick EngineTask).
 */
public final class TargetTracker {

    private static final int CAP = 80;          // ~4 s of history
    private static final long STALE_NS = 6_000_000_000L;

    /** entityId → ring of [nanos, minX,minY,minZ, maxX,maxY,maxZ]. */
    private final Map<Integer, Deque<double[]>> hist = new HashMap<>();

    public void snapshot(long nanos, Collection<Entity> entities) {
        for (Entity e : entities) {
            if (!(e instanceof LivingEntity)) continue;
            BoundingBox b = e.getBoundingBox();
            Deque<double[]> q = hist.computeIfAbsent(e.getEntityId(), k -> new ArrayDeque<>());
            q.addLast(new double[]{ nanos,
                    b.getMinX(), b.getMinY(), b.getMinZ(),
                    b.getMaxX(), b.getMaxY(), b.getMaxZ() });
            while (q.size() > CAP) q.removeFirst();
        }
        // Aggressive eviction: stale entries forever-old never evicted in the
        // old "size > 96" path. Run cutoff every snapshot so a victim that
        // walked out of the 14-block radius doesn't return ancient AABBs to
        // boxAt() if the entityId is later re-queried.
        long cutoff = nanos - STALE_NS;
        hist.values().removeIf(q -> q.isEmpty() || q.peekLast()[0] < cutoff);
    }

    /**
     * The victim's hitbox at-or-before {@code nanos} — the snapshot whose
     * timestamp is the largest value ≤ nanos. This is what lag compensation
     * actually needs: the victim's geometry as the server knew it when the
     * attack arrived. The previous "closest in absolute time" picked the
     * AFTER-attack snapshot (EngineTask snapshots at start-of-tick BEFORE
     * draining intake, so the most-recent snapshot is post-attack), silently
     * inverting the rewind. Falls back to the oldest snapshot if no
     * at-or-before exists (entity only just entered tracking).
     *
     * @return {minX,minY,minZ,maxX,maxY,maxZ} or null if unknown.
     */
    public double[] boxAt(int entityId, long nanos) {
        Deque<double[]> q = hist.get(entityId);
        if (q == null || q.isEmpty()) return null;
        double[] before = null, after = null;
        long beforeStamp = Long.MIN_VALUE, afterStamp = Long.MAX_VALUE;
        for (double[] snap : q) {
            long stamp = (long) snap[0];
            if (stamp <= nanos && stamp > beforeStamp) {
                beforeStamp = stamp;
                before = snap;
            } else if (stamp > nanos && stamp < afterStamp) {
                afterStamp = stamp;
                after = snap;
            }
        }
        if (before == null) before = q.peekFirst();   // entity newer than the attack
        if (before == null) return null;

        // Sub-tick lerp between bracketing snapshots when both exist. The
        // server snapshots at start-of-tick, so an attack arriving 25ms into
        // a tick sits halfway between two snapshots. The nearest-before alone
        // can be a full 50ms (≈0.2 blocks on a sprinting victim) stale; lerp
        // brings the rewound AABB exactly to the attack instant, tightening
        // ReachCheck/AimE/HitValidation without any FP risk.
        if (after != null && afterStamp > beforeStamp) {
            double alpha = (double) (nanos - beforeStamp)
                    / (double) (afterStamp - beforeStamp);
            if (alpha < 0) alpha = 0;
            else if (alpha > 1) alpha = 1;
            return new double[]{
                    before[1] + (after[1] - before[1]) * alpha,
                    before[2] + (after[2] - before[2]) * alpha,
                    before[3] + (after[3] - before[3]) * alpha,
                    before[4] + (after[4] - before[4]) * alpha,
                    before[5] + (after[5] - before[5]) * alpha,
                    before[6] + (after[6] - before[6]) * alpha,
            };
        }
        return new double[]{ before[1], before[2], before[3], before[4], before[5], before[6] };
    }

    /** Raw snapshots for an entity, oldest→newest (copies): [nanos,minX,minY,minZ,maxX,maxY,maxZ]. */
    public java.util.List<double[]> history(int entityId) {
        Deque<double[]> q = hist.get(entityId);
        if (q == null || q.isEmpty()) return java.util.List.of();
        java.util.List<double[]> out = new java.util.ArrayList<>(q.size());
        for (double[] s : q) out.add(s.clone());
        return out;
    }

    public void clear() { hist.clear(); }
}
