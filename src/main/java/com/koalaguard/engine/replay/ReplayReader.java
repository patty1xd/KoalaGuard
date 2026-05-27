package com.koalaguard.engine.replay;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.zip.GZIPInputStream;

/**
 * Inverse of {@link ReplayWriter}. Returns a {@link Loaded} bundle with the
 * decoded metadata + chronological frame list. Each frame's {@code timeNanos}
 * is rebuilt as {@code deltaMs * 1_000_000} so {@link ReplayPlayer} can drive
 * playback directly without re-rebasing.
 */
public final class ReplayReader {

    public record Loaded(UUID uuid, String name, String reason,
                         long savedAtMs, int durationMs, List<ReplayFrame> frames) { }

    private ReplayReader() { }

    public static Loaded read(File in) throws IOException {
        try (DataInputStream i = new DataInputStream(
                new BufferedInputStream(new GZIPInputStream(new FileInputStream(in))))) {
            int magic = i.readInt();
            if (magic != ReplayWriter.MAGIC) {
                throw new IOException("Not a KoalaGuard replay (bad magic): " + in.getName());
            }
            int version = i.readByte() & 0xFF;
            if (version != ReplayWriter.VERSION) {
                throw new IOException("Unsupported replay version " + version);
            }
            long hi = i.readLong(), lo = i.readLong();
            UUID uuid = new UUID(hi, lo);
            String name = i.readUTF();
            String reason = i.readUTF();
            long savedAt = i.readLong();
            int duration = i.readInt();
            int count = i.readInt();

            List<ReplayFrame> frames = new ArrayList<>(count);
            for (int n = 0; n < count; n++) {
                int dt = i.readInt();
                int ord = i.readByte() & 0xFF;
                ReplayKind kind = ReplayKind.of(ord);
                if (kind == null) throw new IOException("Unknown kind ordinal " + ord);
                ReplayFrame f = new ReplayFrame(dt * 1_000_000L, kind);
                readPayload(i, f);
                frames.add(f);
            }
            return new Loaded(uuid, name, reason, savedAt, duration, frames);
        }
    }

    private static void readPayload(DataInputStream i, ReplayFrame f) throws IOException {
        switch (f.kind) {
            case SPAWN, MOVE -> {
                f.x = i.readDouble(); f.y = i.readDouble(); f.z = i.readDouble();
                f.yaw = i.readFloat(); f.pitch = i.readFloat();
                f.onGround = i.readBoolean();
            }
            case POS -> {
                f.x = i.readDouble(); f.y = i.readDouble(); f.z = i.readDouble();
                f.onGround = i.readBoolean();
            }
            case ROTATE -> {
                f.yaw = i.readFloat(); f.pitch = i.readFloat();
                f.onGround = i.readBoolean();
            }
            case ATTACK -> {
                f.intA = i.readInt();
                f.yaw = i.readFloat(); f.pitch = i.readFloat();
            }
            case ANIMATE -> {
                f.yaw = i.readFloat(); f.pitch = i.readFloat();
            }
            case HELD_ITEM -> f.intA = i.readInt();
            case BLOCK_PLACE, DIG -> {
                f.x = i.readInt(); f.y = i.readInt(); f.z = i.readInt();
                f.byteA = i.readByte();
            }
            case INV_CLICK -> {
                f.intA = i.readInt(); f.intB = i.readInt();
            }
            case HEALTH, HURT -> {
                f.yaw = i.readFloat(); f.pitch = i.readFloat();
            }
            case SNEAK_START, SNEAK_STOP, SPRINT_START, SPRINT_STOP,
                 USE_ITEM, INV_CLOSE, DEATH -> { /* no payload */ }
        }
    }
}
