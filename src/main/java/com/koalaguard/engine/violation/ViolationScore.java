package com.koalaguard.engine.violation;

/**
 * Per-player, per-check divergence accumulator.
 *
 * The whole engine's detection philosophy lives here: a single suspicious
 * tick is meaningless, PERSISTENT divergence is the signal. {@link #add}
 * raises the score on a bad tick, {@link #recover} bleeds it on a clean tick,
 * and {@link #consume} only returns true when the score has stayed elevated
 * long enough to be a confirmed state-transition violation (not a spike).
 */
public final class ViolationScore {

    private double score;
    private int badStreak;
    private int cleanStreak;
    // NOT Long.MIN_VALUE: `tick - lastConfirmTick` would overflow to a huge
    // negative on the first check and the cooldown gate would never pass, so
    // no violation could ever confirm. A large negative sentinel keeps the
    // subtraction safe and lets the first confirmation through immediately.
    private boolean everConfirmed;
    private long lastConfirmTick;

    /** Raise the score; returns the new value. */
    public double add(double amount) {
        score = Math.min(100.0, score + amount);
        badStreak++;
        cleanStreak = 0;
        return score;
    }

    /** Clean tick — decay toward zero. */
    public void recover(double amount) {
        score = Math.max(0.0, score - amount);
        cleanStreak++;
        if (cleanStreak >= 3) badStreak = 0;
    }

    public void reset() {
        score = 0;
        badStreak = 0;
        cleanStreak = 0;
    }

    /**
     * @return true exactly once when the violation becomes "confirmed":
     *         score over threshold AND a minimum bad streak (persistence),
     *         rate-limited so one sustained cheat is one flag, not a flood.
     */
    public boolean consume(double threshold, int minStreak, long tick, int cooldownTicks) {
        boolean cooled = !everConfirmed || (tick - lastConfirmTick) >= cooldownTicks;
        if (score >= threshold && badStreak >= minStreak && cooled) {
            everConfirmed = true;
            lastConfirmTick = tick;
            score *= 0.5;        // bleed, don't zero — repeat offenders escalate
            return true;
        }
        return false;
    }

    public double score()      { return score; }
    public int badStreak()     { return badStreak; }
}
