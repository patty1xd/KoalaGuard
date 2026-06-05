package com.koalaguard.util;

import java.util.Collection;

/**
 * Lightweight statistical helpers used by the behavioural checks.
 * All methods are null/empty safe and allocation-free where possible.
 */
public final class MathUtil {

    private MathUtil() {}

    public static double average(Collection<? extends Number> values) {
        if (values == null || values.isEmpty()) return 0.0;
        double sum = 0.0;
        for (Number n : values) sum += n.doubleValue();
        return sum / values.size();
    }

    public static double variance(Collection<? extends Number> values) {
        if (values == null || values.size() < 2) return Double.MAX_VALUE;
        double mean = average(values);
        double sum = 0.0;
        for (Number n : values) {
            double d = n.doubleValue() - mean;
            sum += d * d;
        }
        return sum / values.size();
    }

    public static double standardDeviation(Collection<? extends Number> values) {
        return Math.sqrt(variance(values));
    }

    /**
     * Number of values that are repeated more than once (low = machine-like).
     * Used to detect "perfectly even" automated timing.
     */
    public static int duplicates(Collection<? extends Number> values) {
        if (values == null) return 0;
        java.util.HashMap<Long, Integer> seen = new java.util.HashMap<>();
        int dupes = 0;
        for (Number n : values) {
            long key = Math.round(n.doubleValue());
            int c = seen.merge(key, 1, Integer::sum);
            if (c == 2) dupes++;
        }
        return dupes;
    }

    /**
     * Greatest-common-divisor of two doubles, used for rotation/GCD analysis.
     * A real mouse produces tiny irregular rotation steps; cheat clients that
     * snap rotations produce a measurable, repeating granularity.
     */
    public static double gcd(double a, double b) {
        a = Math.abs(a);
        b = Math.abs(b);
        if (b < 1.0E-4) return a;
        return gcd(b, a % b);
    }

    public static float wrapAngle(float angle) {
        angle %= 360.0f;
        if (angle >= 180.0f) angle -= 360.0f;
        if (angle < -180.0f) angle += 360.0f;
        return angle;
    }

    public static double clamp(double v, double min, double max) {
        return v < min ? min : Math.min(v, max);
    }

    public static double round(double v, int places) {
        double f = Math.pow(10, places);
        return Math.round(v * f) / f;
    }

    // ───────── statistical anomaly detection ─────────

    /**
     * Shannon entropy (bits) of the value distribution, bucketed. Human input
     * is high-entropy/irregular; automation collapses entropy. Low entropy on
     * a large sample = machine.
     */
    public static double entropy(Collection<? extends Number> values) {
        if (values == null || values.size() < 2) return Double.MAX_VALUE;
        java.util.HashMap<Long, Integer> hist = new java.util.HashMap<>();
        for (Number n : values) hist.merge((long) Math.floor(n.doubleValue()), 1, Integer::sum);
        double n = values.size(), h = 0.0;
        for (int c : hist.values()) {
            double p = c / n;
            h -= p * (Math.log(p) / Math.log(2));
        }
        return h;
    }

    /** Excess kurtosis — autoclickers/aimbots produce abnormally peaked or flat distributions. */
    public static double kurtosis(Collection<? extends Number> values) {
        if (values == null || values.size() < 4) return 0.0;
        double mean = average(values);
        double sd = standardDeviation(values);
        if (sd < 1.0E-6) return 1.0E9;
        double s = 0.0;
        for (Number v : values) { double z = (v.doubleValue() - mean) / sd; s += z * z * z * z; }
        return s / values.size() - 3.0;
    }

    public static double skewness(Collection<? extends Number> values) {
        if (values == null || values.size() < 3) return 0.0;
        double mean = average(values);
        double sd = standardDeviation(values);
        if (sd < 1.0E-6) return 0.0;
        double s = 0.0;
        for (Number v : values) { double z = (v.doubleValue() - mean) / sd; s += z * z * z; }
        return s / values.size();
    }

    /** Repeating granularity of a series (e.g. rotation GCD). 0 if none meaningful. */
    public static double seriesGcd(java.util.List<? extends Number> values) {
        if (values == null || values.size() < 3) return 0.0;
        double g = 0.0;
        for (Number v : values) {
            double x = Math.abs(v.doubleValue());
            if (x < 1.0E-4) continue;
            g = g == 0.0 ? x : gcd(g, x);
        }
        return g;
    }

    public static double clampD(double v, double min, double max) {
        return v < min ? min : Math.min(v, max);
    }

    /**
     * Lag-1 autocorrelation: measures serial dependence in movement/rotation sequences.
     * Human input is largely uncorrelated (r ≈ 0); bots/macros show strong positive correlation
     * (r > 0.5) due to predictable state-machines. Detects non-random movement patterns.
     */
    public static double autoCorrelationLag1(Collection<? extends Number> values) {
        if (values == null || values.size() < 2) return 0.0;
        double mean = average(values);
        double c0 = 0.0, c1 = 0.0;
        java.util.List<? extends Number> list = values instanceof java.util.List ?
            (java.util.List<? extends Number>) values : new java.util.ArrayList<>(values);
        for (int i = 0; i < list.size(); i++) {
            double x = list.get(i).doubleValue() - mean;
            c0 += x * x;
            if (i > 0) c1 += x * (list.get(i - 1).doubleValue() - mean);
        }
        return c1 / c0;
    }
}
