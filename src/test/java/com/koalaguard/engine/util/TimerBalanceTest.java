package com.koalaguard.engine.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the timer-balance contract: vanilla cadence holds ~0, transport stalls
 * never push the balance positive, and only a fast client clock can.
 */
class TimerBalanceTest {

    private static final long TICK_NS = 50_000_000L;
    private static final double RATE = 1.005;
    private static final double FLOOR = -90.0;

    @Test
    void vanillaCadenceStaysNearZero() {
        TimerBalance tb = new TimerBalance();
        long now = 0, count = 0;
        tb.sample(now, count, RATE, FLOOR);
        // 30 s of exact one-packet-per-tick.
        for (int i = 0; i < 600; i++) {
            now += TICK_NS;
            count += 1;
            tb.sample(now, count, RATE, FLOOR);
        }
        assertTrue(tb.balance() <= 0.5, "vanilla cadence must not accrue surplus");
        assertTrue(tb.balance() > -10.0, "tolerance drift should stay tiny over 30s");
    }

    @Test
    void doubleSpeedTimerClimbs() {
        TimerBalance tb = new TimerBalance();
        long now = 0, count = 0;
        tb.sample(now, count, RATE, FLOOR);
        // 2x timer: 2 packets per real tick for 2 s.
        double bal = 0;
        for (int i = 0; i < 40; i++) {
            now += TICK_NS;
            count += 2;
            bal = tb.sample(now, count, RATE, FLOOR);
        }
        assertTrue(bal > 25.0, "2x timer must cross the default threshold inside 2s, was " + bal);
    }

    @Test
    void lagStallThenBurstNeverGoesPositive() {
        TimerBalance tb = new TimerBalance();
        long now = 0, count = 0;
        tb.sample(now, count, RATE, FLOOR);
        // 3 s of total silence (TCP stall)…
        now += 60 * TICK_NS;
        tb.sample(now, count, RATE, FLOOR);
        // …then the queued 60 packets all arrive in one tick.
        now += TICK_NS;
        count += 60;
        double bal = tb.sample(now, count, RATE, FLOOR);
        assertTrue(bal <= 0.5, "catch-up burst restores balance, never exceeds it: " + bal);
    }

    @Test
    void debtFloorPreventsBanking() {
        TimerBalance tb = new TimerBalance();
        long now = 0, count = 0;
        tb.sample(now, count, RATE, FLOOR);
        // 60 s of silence — without the floor this would bank -1200 ticks.
        now += 1200 * TICK_NS;
        double bal = tb.sample(now, count, RATE, FLOOR);
        assertEquals(FLOOR, bal, 1e-9);
        // A cheat then sending 2x for 10 s gains 200 surplus packets —
        // far beyond what the capped debt can hide.
        for (int i = 0; i < 200; i++) {
            now += TICK_NS;
            count += 2;
            bal = tb.sample(now, count, RATE, FLOOR);
        }
        assertTrue(bal > 25.0, "capped debt must not absorb a sustained timer: " + bal);
    }

    @Test
    void resetZeroesBalanceButKeepsAnchors() {
        TimerBalance tb = new TimerBalance();
        long now = 0, count = 0;
        tb.sample(now, count, RATE, FLOOR);
        now += TICK_NS;
        count += 30;
        tb.sample(now, count, RATE, FLOOR);
        assertTrue(tb.balance() > 20.0);
        tb.reset();
        assertEquals(0.0, tb.balance(), 1e-9);
        // Subsequent vanilla cadence stays at zero (anchors were kept — the
        // 30-packet burst is not re-counted).
        now += TICK_NS;
        count += 1;
        double bal = tb.sample(now, count, RATE, FLOOR);
        assertTrue(Math.abs(bal) < 0.5, "post-reset vanilla cadence must be ~0: " + bal);
    }

    @Test
    void backwardClockSampleIsHarmless() {
        TimerBalance tb = new TimerBalance();
        tb.sample(1_000_000_000L, 100, RATE, FLOOR);
        // Anomalous non-monotonic timestamp: elapsed clamps to 0, the packets
        // still count — strictly conservative (can only under-flag).
        double bal = tb.sample(999_999_000L, 101, RATE, FLOOR);
        assertTrue(bal <= 1.0 + 1e-9);
    }
}
