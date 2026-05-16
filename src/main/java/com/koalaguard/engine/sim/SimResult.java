package com.koalaguard.engine.sim;

/**
 * The simulated envelope for ONE upcoming movement tick. Checks compare the
 * actually-observed delta against this envelope and accumulate the divergence;
 * a single tick outside it is never a flag — only persistent divergence is.
 */
public final class SimResult {

    /** Largest horizontal distance vanilla physics permits this tick. */
    public double maxHorizontal;

    /** Predicted vertical delta and the legal band around it. */
    public double expectedDy;
    public double dyLow;
    public double dyHigh;

    /** True if the world/sim state makes vertical prediction unreliable. */
    public boolean verticalReliable = true;
    public boolean horizontalReliable = true;

    public String note = "";
}
