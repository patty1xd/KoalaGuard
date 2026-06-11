package com.koalaguard.util;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MathUtilTest {

    @Test
    void wrapAngleStaysInHalfOpenRange() {
        assertEquals(0f, MathUtil.wrapAngle(360f), 1e-6f);
        assertEquals(-180f, MathUtil.wrapAngle(180f), 1e-6f);   // 180 wraps to -180
        assertEquals(170f, MathUtil.wrapAngle(-190f), 1e-6f);
        assertEquals(-170f, MathUtil.wrapAngle(190f), 1e-6f);
        assertEquals(1f, MathUtil.wrapAngle(721f), 1e-4f);
        for (float a = -1000f; a <= 1000f; a += 7.3f) {
            float w = MathUtil.wrapAngle(a);
            assertTrue(w >= -180f && w < 180f, "out of range for " + a + ": " + w);
        }
    }

    @Test
    void averageAndDeviation() {
        assertEquals(0.0, MathUtil.average(List.of()), 1e-9);
        assertEquals(2.0, MathUtil.average(List.of(1, 2, 3)), 1e-9);
        assertEquals(0.0, MathUtil.standardDeviation(List.of(5, 5, 5, 5)), 1e-9);
        // Population SD of {2,4,4,4,5,5,7,9} is exactly 2.
        assertEquals(2.0, MathUtil.standardDeviation(List.of(2, 4, 4, 4, 5, 5, 7, 9)), 1e-9);
    }

    @Test
    void varianceOfTinySampleIsSentinel() {
        // Size <2 returns MAX_VALUE so callers treat it as "no information",
        // never as "machine-perfect zero variance".
        assertEquals(Double.MAX_VALUE, MathUtil.variance(List.of(3)), 0.0);
    }

    @Test
    void gcdFindsRotationGranularity() {
        assertEquals(0.15, MathUtil.gcd(0.45, 0.60), 1e-6);
        assertEquals(0.15, MathUtil.seriesGcd(List.of(0.45, 0.60, 0.30, 1.05)), 1e-6);
        assertEquals(0.0, MathUtil.seriesGcd(List.of(0.45, 0.60)), 1e-9, "needs ≥3 samples");
    }

    @Test
    void duplicatesCountsRepeatedRoundedValues() {
        assertEquals(0, MathUtil.duplicates(List.of(1, 2, 3)));
        // 5 appears 3x (counted once as a duplicate value), 7 appears 2x.
        assertEquals(2, MathUtil.duplicates(List.of(5, 5, 5, 7, 7, 9)));
    }

    @Test
    void entropyCollapsesForMachineTiming() {
        // Identical values bucket together: zero bits. Spread values don't.
        assertEquals(0.0, MathUtil.entropy(List.of(50, 50, 50, 50)), 1e-9);
        double spread = MathUtil.entropy(List.of(10, 20, 30, 40, 50, 60, 70, 80));
        assertEquals(3.0, spread, 1e-9, "8 distinct buckets = 3 bits");
    }

    @Test
    void autocorrelationSeparatesAlternatingFromConstantTrend() {
        // Strictly alternating series → strong NEGATIVE lag-1 correlation.
        double alt = MathUtil.autoCorrelationLag1(List.of(1, -1, 1, -1, 1, -1, 1, -1));
        assertTrue(alt < -0.5, "alternating must be strongly negative: " + alt);
        // Slowly varying ramp → strong positive.
        double ramp = MathUtil.autoCorrelationLag1(List.of(1, 2, 3, 4, 5, 6, 7, 8));
        assertTrue(ramp > 0.5, "ramp must be strongly positive: " + ramp);
    }

    @Test
    void clampBounds() {
        assertEquals(1.0, MathUtil.clamp(0.5, 1.0, 2.0), 1e-9);
        assertEquals(2.0, MathUtil.clamp(2.5, 1.0, 2.0), 1e-9);
        assertEquals(1.5, MathUtil.clamp(1.5, 1.0, 2.0), 1e-9);
    }
}
