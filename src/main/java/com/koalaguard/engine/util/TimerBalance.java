package com.koalaguard.engine.util;

/**
 * Client-clock balance model for timer detection — pure math, no Bukkit.
 *
 * A vanilla client emits exactly ONE movement-class packet per client tick,
 * so over real time the long-run rate is a hard 20/s. Each sample adds the
 * packets received since the last sample and subtracts the packets ALLOWED
 * by the real elapsed time. Transport lag only ever delays packets — a TCP
 * stall accrues negative balance during the silence and the catch-up burst
 * restores it toward zero, never above. The only way the balance can climb
 * positive is a client emitting more ticks than real time contains: timer.
 *
 * The debt floor caps how much "credit" a stalled connection can bank, so a
 * cheat cannot lag-switch for a minute and then run timer against the saved
 * deficit forever; it must be deeper than the worst legitimate freeze a
 * catch-up burst can follow (default −90 ticks ≈ 4.5 s).
 */
public final class TimerBalance {

    private static final double NANOS_PER_TICK = 50_000_000.0;

    private long lastNanos = Long.MIN_VALUE;
    private long lastCount;
    private double balance;

    /**
     * Advance the model to {@code nowNanos} with the session-total packet
     * counter at {@code packetCount}.
     *
     * @param allowedRate packets allowed per tick of real time — 1.0 is exact
     *                    vanilla; a small surplus (1.005) absorbs clock drift.
     * @param debtFloor   most negative balance retained (e.g. −90).
     * @return the balance after this sample, in ticks ahead of real time.
     */
    public double sample(long nowNanos, long packetCount,
                         double allowedRate, double debtFloor) {
        if (lastNanos == Long.MIN_VALUE) {
            lastNanos = nowNanos;
            lastCount = packetCount;
            return 0.0;
        }
        double elapsedTicks = (nowNanos - lastNanos) / NANOS_PER_TICK;
        if (elapsedTicks < 0) elapsedTicks = 0;          // clock anomaly guard
        long delta = packetCount - lastCount;
        balance += delta - elapsedTicks * allowedRate;
        if (balance < debtFloor) balance = debtFloor;
        lastNanos = nowNanos;
        lastCount = packetCount;
        return balance;
    }

    /** Zero the balance, keeping counter/clock anchors (grace events). */
    public void reset() {
        balance = 0.0;
    }

    public double balance() {
        return balance;
    }
}
