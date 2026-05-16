package com.koalaguard.engine.packet;

/**
 * One immutable entry in the unified packet log.
 *
 * Every captured packet stores, as required:
 *  • {@link #tickIndex} — the player movement-tick it was bound to,
 *  • {@link #recvNanos} — the netty-thread receive timestamp,
 *  • {@link #stateRef}  — a reference to the player-state snapshot that was
 *    current when the packet was processed (null for packets captured before
 *    the first movement frame).
 *
 * The raw decoded fields are kept generic so a single log can hold every
 * packet kind without per-kind subclasses leaking into the engine.
 */
public final class CapturedPacket {

    public final long seq;
    public final PacketKind kind;
    public final long recvNanos;

    // Decoded payload — only the fields relevant to a given kind are set.
    public double x, y, z;
    public float yaw, pitch;
    public boolean hasPos, hasRot, onGround;
    public int intA = -1, intB = -1;     // entityId / slot / windowId / id
    public String strA;                  // brand / channel
    public Object objA;                  // wrapper-specific extra

    // Assigned on the main thread when the packet is consumed.
    public long tickIndex = -1;
    public Object stateRef;

    public CapturedPacket(long seq, PacketKind kind, long recvNanos) {
        this.seq = seq;
        this.kind = kind;
        this.recvNanos = recvNanos;
    }
}
