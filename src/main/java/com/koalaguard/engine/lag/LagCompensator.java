package com.koalaguard.engine.lag;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Lag-compensation model — built from transaction (Ping/Pong) round trips.
 *
 * DESIGN: no instant ping subtraction. A single RTT sample is noisy and
 * spoof-adjacent; subtracting "ping/2" from one measured delay is exactly the
 * hack this engine avoids. Instead we keep a rolling window and expose:
 *
 *  • {@link #median()}  — robust central latency (outlier-proof),
 *  • {@link #smoothed()} — EMA of the median for slow trend,
 *  • {@link #jitter()}  — median absolute deviation (transport instability).
 *
 * Checks NEVER subtract these from a measured value. They use them only to (a)
 * gate detection when the transport is too unstable to trust the simulation,
 * and (b) widen tolerance windows proportionally to jitter. Tick comparisons
 * use the confirmed-transaction counter (an authoritative clock), not RTT.
 */
public final class LagCompensator {

    private static final int WINDOW = 40;          // ~2 s of transactions
    private final Deque<Integer> samples = new ArrayDeque<>();
    private double ema = -1;

    public synchronized void onTransaction(int rttMs) {
        if (rttMs < 0 || rttMs > 60_000) return;
        samples.addLast(rttMs);
        while (samples.size() > WINDOW) samples.removeFirst();
        double m = median();
        ema = ema < 0 ? m : ema * 0.85 + m * 0.15;
    }

    public synchronized double median() {
        if (samples.isEmpty()) return 0;
        List<Integer> s = new ArrayList<>(samples);
        s.sort(Integer::compareTo);
        int n = s.size();
        return n % 2 == 1 ? s.get(n / 2) : (s.get(n / 2 - 1) + s.get(n / 2)) / 2.0;
    }

    public synchronized double smoothed() {
        return ema < 0 ? median() : ema;
    }

    /** Median absolute deviation — high = unstable connection. */
    public synchronized double jitter() {
        if (samples.size() < 4) return 0;
        double med = median();
        List<Double> dev = new ArrayList<>(samples.size());
        for (int v : samples) dev.add(Math.abs(v - med));
        dev.sort(Double::compareTo);
        int n = dev.size();
        return n % 2 == 1 ? dev.get(n / 2) : (dev.get(n / 2 - 1) + dev.get(n / 2)) / 2.0;
    }

    /**
     * Number of movement ticks of slack a check should allow for transport
     * noise. Derived from jitter only (NOT from raw ping), capped so a bad
     * connection can never grant an unbounded bypass.
     */
    public synchronized int toleranceTicks() {
        return (int) Math.min(8, Math.ceil(jitter() / 50.0)) + 1;
    }

    /** Transport is too unstable to trust physics replication this tick. */
    public synchronized boolean unstable() {
        return samples.size() >= 6
                && (smoothed() >= 400 || jitter() >= 180);
    }
}
