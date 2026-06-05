package com.koalaguard.engine.replay;

import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import java.util.zip.Deflater;
import java.util.zip.GZIPOutputStream;

/**
 * Serialises a rolling {@link ReplayBuffer} snapshot to disk as a single
 * GZIP-compressed binary file. Layout (all big-endian):
 *
 * <pre>
 * header:
 *   magic    int    "KGR1" (0x4B475231)
 *   version  byte   1
 *   uuidHi   long
 *   uuidLo   long
 *   name     utf
 *   reason   utf
 *   savedAt  long   epoch-millis (wall clock at save)
 *   duration int    ms covered (last.delta - first.delta)
 *   count    int    number of frames
 * frames[count]:
 *   deltaMs  int    ms since first.timeNanos
 *   kind     byte   ordinal()
 *   payload  variable — see {@link #writePayload}
 * </pre>
 *
 * The format is intentionally trivial: one writer, one reader, no third-party
 * deps. {@link Deflater#BEST_SPEED} keeps the on-ban save cheap; rolling
 * buffers are small enough that this gives ≥80% ratio in practice.
 */
public final class ReplayWriter {

    public static final int MAGIC = 0x4B475231;
    public static final byte VERSION = 1;

    private ReplayWriter() { }

    public static void write(File out, UUID uuid, String name, String reason,
                             List<ReplayFrame> frames) throws IOException {
        if (frames == null || frames.isEmpty()) return;
        File parent = out.getParentFile();
        if (parent != null) parent.mkdirs();

        long base = frames.get(0).timeNanos;
        int duration = (int) ((frames.get(frames.size() - 1).timeNanos - base) / 1_000_000L);

        GZIPOutputStream gz = new GZIPOutputStream(
                new BufferedOutputStream(new FileOutputStream(out))) {
            { def.setLevel(Deflater.BEST_SPEED); }
        };
        try (DataOutputStream o = new DataOutputStream(gz)) {
            o.writeInt(MAGIC);
            o.writeByte(VERSION);
            o.writeLong(uuid.getMostSignificantBits());
            o.writeLong(uuid.getLeastSignificantBits());
            o.writeUTF(name == null ? "" : name);
            o.writeUTF(reason == null ? "" : reason);
            o.writeLong(System.currentTimeMillis());
            o.writeInt(duration);
            o.writeInt(frames.size());

            for (ReplayFrame f : frames) {
                int dt = (int) ((f.timeNanos - base) / 1_000_000L);
                o.writeInt(dt);
                o.writeByte(f.kind.ordinal());
                writePayload(o, f);
            }
        }
    }

    private static void writePayload(DataOutputStream o, ReplayFrame f) throws IOException {
        switch (f.kind) {
            case SPAWN, MOVE -> {
                o.writeDouble(f.x); o.writeDouble(f.y); o.writeDouble(f.z);
                o.writeFloat(f.yaw); o.writeFloat(f.pitch);
                o.writeBoolean(f.onGround);
            }
            case POS -> {
                o.writeDouble(f.x); o.writeDouble(f.y); o.writeDouble(f.z);
                o.writeBoolean(f.onGround);
            }
            case ROTATE -> {
                o.writeFloat(f.yaw); o.writeFloat(f.pitch);
                o.writeBoolean(f.onGround);
            }
            case ATTACK -> {
                o.writeInt(f.intA);
                o.writeFloat(f.yaw); o.writeFloat(f.pitch);
            }
            case ANIMATE -> {
                o.writeFloat(f.yaw); o.writeFloat(f.pitch);
            }
            case HELD_ITEM -> o.writeInt(f.intA);
            case BLOCK_PLACE, DIG -> {
                o.writeInt((int) f.x); o.writeInt((int) f.y); o.writeInt((int) f.z);
                o.writeByte(f.byteA);
            }
            case INV_CLICK -> {
                o.writeInt(f.intA); o.writeInt(f.intB);
            }
            case HEALTH, HURT -> {
                o.writeFloat(f.yaw); o.writeFloat(f.pitch);
            }
            case FLAG -> {
                o.writeInt(f.intA);                 // check ord / id
                o.writeInt(f.intB);                 // score (×100 fixed-point)
                o.writeByte(f.byteA);               // severity (0-255)
                o.writeFloat(f.yaw);                // health snapshot
                o.writeFloat(f.pitch);              // food snapshot
                o.writeDouble(f.x); o.writeDouble(f.y); o.writeDouble(f.z);
            }
            case SNEAK_START, SNEAK_STOP, SPRINT_START, SPRINT_STOP,
                 USE_ITEM, INV_CLOSE, DEATH -> { /* no payload */ }
        }
    }
}
