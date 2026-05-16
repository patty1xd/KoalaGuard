package com.koalaguard.engine.state;

/**
 * Immutable kinematic snapshot for exactly ONE client movement tick.
 *
 * Deltas are computed packet-to-packet (the way the client itself integrates
 * physics), never sampled at the server-tick boundary — so a tick with no move
 * packet is never a phantom "0 motion" and two packets in a tick are never a
 * phantom "2x speed". This is the unit the simulator predicts against.
 */
public final class PositionFrame {

    public final long tick;
    public final double x, y, z;
    public final double dx, dy, dz;          // velocity this tick (pos - prevPos)
    public final double prevX, prevY, prevZ;
    public final float yaw, pitch;
    public final float dYaw, dPitch;
    public final boolean clientGround;

    // Filled by the engine after world is consulted (main thread only).
    public boolean simGround;
    public boolean collidedHorizontally;
    public boolean collidedVertically;
    public boolean insideSolid;
    public double groundSlipperiness = 0.6;

    public PositionFrame(long tick,
                         double x, double y, double z,
                         double prevX, double prevY, double prevZ,
                         float yaw, float pitch, float dYaw, float dPitch,
                         boolean clientGround) {
        this.tick = tick;
        this.x = x; this.y = y; this.z = z;
        this.prevX = prevX; this.prevY = prevY; this.prevZ = prevZ;
        this.dx = x - prevX; this.dy = y - prevY; this.dz = z - prevZ;
        this.yaw = yaw; this.pitch = pitch;
        this.dYaw = dYaw; this.dPitch = dPitch;
        this.clientGround = clientGround;
    }

    public double horizontalSpeed() { return Math.sqrt(dx * dx + dz * dz); }
}
