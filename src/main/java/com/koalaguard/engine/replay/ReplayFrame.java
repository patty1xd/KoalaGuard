package com.koalaguard.engine.replay;

/**
 * One immutable-by-convention entry in a player's replay buffer. The class is
 * intentionally a tiny POJO — checks NEVER read this; only the buffer and the
 * serialiser do. {@code timeNanos} is wall-monotonic ({@link System#nanoTime}),
 * rebased to a delta-ms-from-buffer-start at serialisation time.
 */
public final class ReplayFrame {

    public final long timeNanos;
    public final ReplayKind kind;

    public double x, y, z;
    public float yaw, pitch;
    public boolean onGround;
    public int intA = -1, intB = -1;
    public byte byteA;

    /** Monotonic frame id assigned by the buffer at push time. Optional in
     *  serialised form (version-bumped writers can persist it; v1 ignores).
     *  Used to link a violation event to the exact replay frame at flag-time. */
    public long seqId;

    public ReplayFrame(long timeNanos, ReplayKind kind) {
        this.timeNanos = timeNanos;
        this.kind = kind;
    }
}
