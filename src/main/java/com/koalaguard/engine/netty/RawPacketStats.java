package com.koalaguard.engine.netty;

import java.util.concurrent.atomic.LongAdder;

/**
 * Per-player byte-level traffic accumulator owned by {@link RawPacketSniffer}.
 *
 * Lock-free counters for the hot path (every netty frame); checks read the
 * aggregates main-thread. Two time windows: lifetime totals (LongAdder) and
 * a 1-second rolling histogram (per-decisecond buckets) so a check can ask
 * "how many bytes did this player send in the last 500ms" without scanning
 * any packet log.
 *
 * Per-id frame counter ([0..255]) lets checks notice unusual packet-id
 * fingerprints (e.g. a flood of one specific id, or transmissions of an id
 * the vanilla client never sends).
 */
public final class RawPacketStats {

    /** Lifetime counters. */
    public final LongAdder inFrames  = new LongAdder();
    public final LongAdder inBytes   = new LongAdder();
    public final LongAdder outFrames = new LongAdder();
    public final LongAdder outBytes  = new LongAdder();

    /** Per-packet-id frame counts (vanilla protocol = ≤256 ids). */
    private final LongAdder[] idCounts = new LongAdder[256];

    /** 1-second rolling bucket ring — 10 buckets × 100ms each. */
    private static final int BUCKETS = 10;
    private static final long BUCKET_NS = 100_000_000L;
    private final long[] bucketByteIn = new long[BUCKETS];
    private final long[] bucketByteOut = new long[BUCKETS];
    private final long[] bucketStart = new long[BUCKETS];
    private volatile int bucketIdx;

    /** Last receive nanos — used for inter-frame jitter measurement. */
    public volatile long lastInboundNanos = 0;
    /** Largest single inbound frame seen (max-frame anomaly source). */
    public volatile int maxInboundLen;

    public RawPacketStats() {
        for (int i = 0; i < idCounts.length; i++) idCounts[i] = new LongAdder();
    }

    /** Called from the netty thread for every inbound frame. */
    public void recordInbound(long nowNs, int len, int firstByte) {
        inFrames.increment();
        inBytes.add(len);
        if (firstByte >= 0 && firstByte < 256) idCounts[firstByte].increment();
        if (len > maxInboundLen) maxInboundLen = len;
        lastInboundNanos = nowNs;
        rollAndAdd(nowNs, len, true);
    }

    /** Called from the netty thread for every outbound frame. */
    public void recordOutbound(long nowNs, int len) {
        outFrames.increment();
        outBytes.add(len);
        rollAndAdd(nowNs, len, false);
    }

    private synchronized void rollAndAdd(long nowNs, int len, boolean inbound) {
        // Advance the bucket ring forward to the current decisecond.
        long curStart = bucketStart[bucketIdx];
        while (nowNs - curStart >= BUCKET_NS) {
            bucketIdx = (bucketIdx + 1) % BUCKETS;
            bucketByteIn[bucketIdx] = 0;
            bucketByteOut[bucketIdx] = 0;
            bucketStart[bucketIdx] = curStart + BUCKET_NS;
            curStart = bucketStart[bucketIdx];
            if (curStart == 0) bucketStart[bucketIdx] = nowNs;
        }
        if (inbound) bucketByteIn[bucketIdx] += len;
        else         bucketByteOut[bucketIdx] += len;
    }

    /** Bytes received in the last {@code windowMs} ms (cap 1000). */
    public synchronized long inboundBytesInWindow(long windowMs) {
        long bucketsBack = Math.min(BUCKETS, Math.max(1, windowMs / 100));
        long sum = 0;
        for (int i = 0; i < bucketsBack; i++) {
            int idx = (bucketIdx - i + BUCKETS) % BUCKETS;
            sum += bucketByteIn[idx];
        }
        return sum;
    }

    /** Frame count for a specific packet id (first-byte) since join. */
    public long countForId(int id) {
        if (id < 0 || id >= idCounts.length) return 0;
        return idCounts[id].sum();
    }
}
