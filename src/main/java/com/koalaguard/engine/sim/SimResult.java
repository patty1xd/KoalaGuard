package com.koalaguard.engine.sim;

/**
 * The simulated envelope for ONE upcoming movement tick. Checks compare the
 * actually-observed delta against this envelope and accumulate the divergence;
 * a single tick outside it is never a flag — only persistent divergence is.
 */
public final class SimResult {

    /** Largest horizontal distance vanilla physics permits this tick. */
    public double maxHorizontal;

    /**
     * Smallest horizontal distance vanilla physics produces this tick, GIVEN
     * the previous-tick speed and current friction state. Detects slow-fly
     * and sneak-cheat exploits that reduce horizontal speed below natural
     * deceleration. Set by the simulator; PredictionCheck enforces
     * {@code actualH >= minHorizontal - epsilon} when reliable.
     */
    public double minHorizontal;

    /** Predicted vertical delta and the legal band around it. */
    public double expectedDy;
    public double dyLow;
    public double dyHigh;

    /** True if the world/sim state makes vertical prediction unreliable. */
    public boolean verticalReliable = true;
    public boolean horizontalReliable = true;

    public String note = "";
}
