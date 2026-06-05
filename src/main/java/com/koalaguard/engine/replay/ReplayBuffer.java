package com.koalaguard.engine.replay;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Per-player rolling circular buffer of {@link ReplayFrame}s spanning a fixed
 * wall-clock window (default 30 s). Frames older than {@code windowNanos} are
 * evicted from the head on every push.
 *
 * The buffer is touched by the netty thread (push) and the main thread
 * (snapshot/save), so all operations synchronise on {@code this}. Memory stays
 * bounded by the time window — at a worst-case 20 movement Hz plus aux packets
 * a 30 s window holds a few hundred frames per player.
 */
public final class ReplayBuffer {

    private final long windowNanos;
    private final Deque<ReplayFrame> frames = new ArrayDeque<>();

    public ReplayBuffer(int windowSeconds) {
        this.windowNanos = windowSeconds * 1_000_000_000L;
    }

    public synchronized void push(ReplayFrame f) {
        if (f == null) return;
        frames.addLast(f);
        long cutoff = f.timeNanos - windowNanos;
        ReplayFrame head;
        while ((head = frames.peekFirst()) != null && head.timeNanos < cutoff) {
            frames.pollFirst();
        }
    }

    /** Defensive copy in chronological order — safe for off-thread writers. */
    public synchronized List<ReplayFrame> snapshot() {
        return new ArrayList<>(frames);
    }

    /**
     * Read-only view of the last {@code count} frames, chronological. Cheaper
     * than {@link #snapshot()} when a check only needs the freshest tail
     * (e.g. on-flag short-window replay extraction).
     */
    public synchronized List<ReplayFrame> peekTail(int count) {
        if (count <= 0 || frames.isEmpty()) return List.of();
        int n = Math.min(count, frames.size());
        ArrayList<ReplayFrame> out = new ArrayList<>(n);
        int skip = frames.size() - n;
        int i = 0;
        for (ReplayFrame f : frames) {
            if (i++ < skip) continue;
            out.add(f);
        }
        return out;
    }

    public synchronized int size() {
        return frames.size();
    }

    public synchronized void clear() {
        frames.clear();
    }
}
