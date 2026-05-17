package com.koalaguard.engine.state;

import com.koalaguard.engine.packet.CapturedPacket;
import com.koalaguard.engine.packet.PacketRingLog;

import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * The continuously-updated, per-player state machine. THIS IS NOT EVENT BASED:
 * {@code EngineTask} advances it every server tick by replaying the queued
 * client packets, one movement frame at a time. Every check reads only from
 * here, so all detection is over reconstructed state transitions.
 *
 * Threading: {@link #intake} is the single hand-off — the netty listener
 * enqueues raw {@link CapturedPacket}s, the main-thread engine drains them.
 * Everything else is touched only by the main thread.
 */
public final class PlayerState {

    public final UUID uuid;
    public final String name;

    /** netty → main hand-off. Per-connection packet order is preserved. */
    public final Queue<CapturedPacket> intake = new ConcurrentLinkedQueue<>();

    /** Tick-indexed processed-packet history (for transition validation). */
    public final PacketRingLog log = new PacketRingLog(512);

    public final InventoryState inv = new InventoryState();
    public final CombatState combat = new CombatState();

    // ── tick-indexed kinematic history ──
    private static final int FRAME_CAP = 256;
    private final PositionFrame[] frames = new PositionFrame[FRAME_CAP];
    private int frameHead, frameCount;

    public long tick;                 // client-movement-tick counter
    public PositionFrame current, previous;

    // baseline for delta reconstruction
    public boolean moveInit;
    public double prevX, prevY, prevZ;
    public float prevYaw, prevPitch;

    public int airTicks, groundTicks, sinceGroundTicks;

    // ── rotation history (true client aim, tick indexed) ──
    private final float[][] rot = new float[128][2];
    private int rotHead, rotCount;

    // ── volatile booleans written by netty, read by main thread ──
    public volatile boolean sprinting, sneaking, usingItem;
    public volatile long usingItemSinceNanos;

    // ── per-tick environment exemptions (set by EngineTask before checks) ──
    public boolean exFlying, exVehicle, exGliding, exClimbing,
                   exLiquid, exLevitation, exSlowFalling, exRiptide,
                   exDead, exSpectator, exWeb;
    /** Tick the player was last in a movement-altering block (web grace). */
    public long lastSpecialBlockTick = Long.MIN_VALUE / 2;

    public PlayerState(UUID uuid, String name) {
        this.uuid = uuid;
        this.name = name;
    }

    // ───────────────── frame history ─────────────────

    public void pushFrame(PositionFrame f) {
        previous = current;
        current = f;
        frames[frameHead] = f;
        frameHead = (frameHead + 1) % FRAME_CAP;
        if (frameCount < FRAME_CAP) frameCount++;
    }

    /** Newest frame whose tick is <= the given tick (lag-compensated lookups). */
    public PositionFrame frameAtOrBefore(long t) {
        int idx = (frameHead - 1 + FRAME_CAP) % FRAME_CAP;
        for (int i = 0; i < frameCount; i++) {
            PositionFrame f = frames[idx];
            if (f != null && f.tick <= t) return f;
            idx = (idx - 1 + FRAME_CAP) % FRAME_CAP;
        }
        return current;
    }

    public List<PositionFrame> recentFrames(int max) {
        List<PositionFrame> out = new ArrayList<>(Math.min(max, frameCount));
        int idx = (frameHead - 1 + FRAME_CAP) % FRAME_CAP;
        for (int i = 0; i < frameCount && out.size() < max; i++) {
            if (frames[idx] != null) out.add(frames[idx]);
            idx = (idx - 1 + FRAME_CAP) % FRAME_CAP;
        }
        return out;   // newest first
    }

    // ───────────────── rotation history ─────────────────

    public void pushRotation(float yaw, float pitch) {
        rot[rotHead][0] = yaw;
        rot[rotHead][1] = pitch;
        rotHead = (rotHead + 1) % rot.length;
        if (rotCount < rot.length) rotCount++;
    }

    /** Absolute yaw deltas (deg), oldest→newest, wrapped to [-180,180]. */
    public List<Float> yawDeltas(int max) {
        return deltas(0, max);
    }
    public List<Float> pitchDeltas(int max) {
        return deltas(1, max);
    }

    private List<Float> deltas(int axis, int max) {
        List<Float> vals = new ArrayList<>();
        int n = Math.min(rotCount, max + 1);
        int idx = (rotHead - n + rot.length) % rot.length;
        Float prev = null;
        for (int i = 0; i < n; i++) {
            float v = rot[idx][axis];
            if (prev != null) {
                float d = axis == 0 ? wrap(v - prev) : (v - prev);
                vals.add(Math.abs(d));
            }
            prev = v;
            idx = (idx + 1) % rot.length;
        }
        return vals;
    }

    private static float wrap(float a) {
        a %= 360f;
        if (a >= 180f) a -= 360f;
        if (a < -180f) a += 360f;
        return a;
    }

    public void reset() {
        moveInit = false;
        intake.clear();
    }
}
