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
        if (hist.size() > 96) {                 // drop despawned / far entities
            long cutoff = nanos - STALE_NS;
            hist.values().removeIf(q -> q.isEmpty() || q.peekLast()[0] < cutoff);
        }
    }

    /**
     * The victim's hitbox closest in time to {@code nanos}.
     * @return {minX,minY,minZ,maxX,maxY,maxZ} or null if unknown.
     */
    public double[] boxAt(int entityId, long nanos) {
        Deque<double[]> q = hist.get(entityId);
        if (q == null || q.isEmpty()) return null;
        double[] best = null;
        long bestDiff = Long.MAX_VALUE;
        for (double[] snap : q) {
            long diff = Math.abs((long) snap[0] - nanos);
            if (diff < bestDiff) { bestDiff = diff; best = snap; }
        }
        if (best == null) return null;
        return new double[]{ best[1], best[2], best[3], best[4], best[5], best[6] };
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
