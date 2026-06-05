package com.koalaguard.engine.packet;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * Bounded, tick-indexed history of processed packets for one player. Checks
 * scan this to validate state TRANSITIONS (e.g. "was there a CLICK_WINDOW
 * moving a totem to slot 45 between the consume tick and the re-equip tick?")
 * instead of measuring isolated event timing.
 *
 * Single-threaded: only the main-thread engine appends/reads it.
 */
public final class PacketRingLog {

    private final CapturedPacket[] buf;
    private int head;      // next write slot
    private int count;

    public PacketRingLog(int capacity) {
        this.buf = new CapturedPacket[capacity];
    }

    public void append(CapturedPacket p) {
        buf[head] = p;
        head = (head + 1) % buf.length;
        if (count < buf.length) count++;
    }

    /** Most-recent-first iteration of the last {@code count} packets. */
    public List<CapturedPacket> recent(int max) {
        List<CapturedPacket> out = new ArrayList<>(Math.min(max, count));
        int idx = (head - 1 + buf.length) % buf.length;
        for (int i = 0; i < count && out.size() < max; i++) {
            CapturedPacket p = buf[idx];
            if (p != null) out.add(p);
            idx = (idx - 1 + buf.length) % buf.length;
        }
        return out;
    }

    /** True if any packet at or after {@code sinceTick} matches the predicate. */
    public boolean existsSinceTick(long sinceTick, Predicate<CapturedPacket> match) {
        int idx = (head - 1 + buf.length) % buf.length;
        for (int i = 0; i < count; i++) {
            CapturedPacket p = buf[idx];
            if (p == null) break;
            if (p.tickIndex < sinceTick) break;          // log is tick-ordered
            if (match.test(p)) return true;
            idx = (idx - 1 + buf.length) % buf.length;
        }
        return false;
    }

    /**
     * Newest packet of a specific kind, or null. O(n) but exits at the first
     * match; checks that only need the most recent attack / animation / etc.
     * no longer iterate the full ring or allocate a list snapshot.
     */
    public CapturedPacket newestOfKind(PacketKind kind) {
        int idx = (head - 1 + buf.length) % buf.length;
        for (int i = 0; i < count; i++) {
            CapturedPacket p = buf[idx];
            if (p != null && p.kind == kind) return p;
            idx = (idx - 1 + buf.length) % buf.length;
        }
        return null;
    }

    /**
     * Count of packets of a given kind whose recvNanos lies within the last
     * {@code windowNs}. Avoids an alloc-per-tick snapshot for rate checks.
     */
    public int countSinceNanos(PacketKind kind, long sinceNanos) {
        int idx = (head - 1 + buf.length) % buf.length;
        int n = 0;
        for (int i = 0; i < count; i++) {
            CapturedPacket p = buf[idx];
            if (p == null) break;
            if (p.recvNanos < sinceNanos) break;          // monotonic
            if (p.kind == kind) n++;
            idx = (idx - 1 + buf.length) % buf.length;
        }
        return n;
    }

    public int size() { return count; }
}
