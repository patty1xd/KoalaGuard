package com.koalaguard.engine.replay;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.protocol.entity.data.EntityData;
import com.github.retrooper.packetevents.protocol.entity.data.EntityDataTypes;
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes;
import com.github.retrooper.packetevents.protocol.player.GameMode;
import com.github.retrooper.packetevents.protocol.player.UserProfile;
import com.github.retrooper.packetevents.protocol.world.Location;
import com.github.retrooper.packetevents.util.Vector3d;
import com.github.retrooper.packetevents.wrapper.PacketWrapper;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDestroyEntities;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityAnimation;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityHeadLook;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityMetadata;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityRotation;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityTeleport;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerInfoRemove;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerInfoUpdate;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnEntity;
import com.koalaguard.KoalaGuard;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Drives a packet-level NPC that replays a saved {@link ReplayFrame} stream to
 * a single viewer. The NPC is invisible to everyone else — every packet is
 * scoped to one Bukkit {@link Player} via {@link PacketEvents}'s
 * {@code getPlayerManager().sendPacket}. No real entity exists on the server.
 *
 * Lifecycle:
 *   1. {@link #start} spawns the player-type entity at the first pose frame,
 *      adds it to the viewer's tab, then schedules a per-tick playback loop.
 *   2. The loop walks {@code frames} in chronological order; for each frame
 *      whose deltaMs has elapsed it emits the appropriate teleport / rotation
 *      / head-look / animation packet.
 *   3. {@link #stop} despawns the entity and removes the tab entry.
 *
 * Wrapper signatures are aligned with {@code FakePlayer.java} so the same
 * PacketEvents 2.12.0 surface (raw {@link UUID} on spawn, {@link Location}-
 * based pose, {@code List<>}-based player-info entries) is used.
 */
public final class ReplayPlayer {

    /** Dedicated high entity-ID space — real servers never reach ~2e9. */
    private static final AtomicInteger EID_GEN = new AtomicInteger(2_100_000_000);

    private final KoalaGuard plugin;
    private final Player viewer;
    private final List<ReplayFrame> frames;
    private final String npcName;
    private final UUID npcUuid;
    private final int entityId;

    private BukkitTask task;
    private long startMs;
    private int idx;
    private boolean running;
    private float speed = 1.0f;
    private boolean paused;
    private long pausedAtMs;

    public ReplayPlayer(KoalaGuard plugin, Player viewer, String label, List<ReplayFrame> frames) {
        this.plugin = plugin;
        this.viewer = viewer;
        this.frames = frames;
        String base = label == null || label.isBlank() ? "Replay" : label;
        String trimmed = base.length() > 12 ? base.substring(0, 12) : base;
        this.npcName = "RP_" + trimmed;
        this.npcUuid = UUID.randomUUID();
        this.entityId = EID_GEN.updateAndGet(v -> v >= 2_140_000_000 ? 2_100_000_000 : v + 1);
    }

    public boolean isRunning() { return running; }
    public Player getViewer() { return viewer; }

    /** Playback speed multiplier, clamped 0.1×–8×. Default 1.0. */
    public void setSpeed(float v) {
        this.speed = Math.max(0.1f, Math.min(8.0f, v));
    }
    public float getSpeed() { return speed; }

    public void pause() {
        if (paused || !running) return;
        paused = true;
        pausedAtMs = System.currentTimeMillis();
    }
    public void resume() {
        if (!paused) return;
        startMs += System.currentTimeMillis() - pausedAtMs;
        paused = false;
    }
    public boolean isPaused() { return paused; }

    public void start() {
        if (running || frames == null || frames.isEmpty()) return;
        ReplayFrame spawn = firstPose();
        if (spawn == null) return;

        try {
            sendTabAdd();
            sendSpawnEntity(spawn);
            sendMetadataVisible();
            send(new WrapperPlayServerEntityHeadLook(entityId, spawn.yaw));
        } catch (Throwable t) {
            plugin.getLogger().warning("Replay NPC spawn failed: " + t);
            return;
        }

        startMs = System.currentTimeMillis();
        idx = 0;
        running = true;
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
    }

    public void stop() {
        if (!running) return;
        running = false;
        if (task != null) { task.cancel(); task = null; }
        if (!viewer.isOnline()) return;
        try { send(new WrapperPlayServerDestroyEntities(entityId)); }
        catch (Throwable ignored) { }
        try { send(new WrapperPlayServerPlayerInfoRemove(Collections.singletonList(npcUuid))); }
        catch (Throwable ignored) { }
    }

    private ReplayFrame firstPose() {
        for (ReplayFrame f : frames) {
            if (f.kind == ReplayKind.SPAWN || f.kind == ReplayKind.MOVE || f.kind == ReplayKind.POS) {
                return f;
            }
        }
        return frames.get(0);
    }

    private void tick() {
        if (!viewer.isOnline()) { stop(); return; }
        if (paused) return;
        long rawElapsed = System.currentTimeMillis() - startMs;
        long elapsed = (long) (rawElapsed * speed);
        while (idx < frames.size()) {
            ReplayFrame f = frames.get(idx);
            long fmS = f.timeNanos / 1_000_000L;
            if (fmS > elapsed) break;
            playFrame(f);
            idx++;
        }
        if (idx >= frames.size()) stop();
    }

    private void playFrame(ReplayFrame f) {
        try {
            switch (f.kind) {
                case MOVE, SPAWN -> {
                    send(new WrapperPlayServerEntityTeleport(
                            entityId, new Vector3d(f.x, f.y, f.z), f.yaw, f.pitch, f.onGround));
                    send(new WrapperPlayServerEntityHeadLook(entityId, f.yaw));
                }
                case POS -> send(new WrapperPlayServerEntityTeleport(
                            entityId, new Vector3d(f.x, f.y, f.z), 0f, 0f, f.onGround));
                case ROTATE -> {
                    send(new WrapperPlayServerEntityRotation(
                            entityId, f.yaw, f.pitch, f.onGround));
                    send(new WrapperPlayServerEntityHeadLook(entityId, f.yaw));
                }
                case ATTACK, ANIMATE -> send(new WrapperPlayServerEntityAnimation(
                            entityId, WrapperPlayServerEntityAnimation.EntityAnimationType.SWING_MAIN_ARM));
                case HURT -> send(new WrapperPlayServerEntityAnimation(
                            entityId, WrapperPlayServerEntityAnimation.EntityAnimationType.HURT));
                default -> { /* sneak/sprint/inv/place/use — no NPC-visible packet */ }
            }
        } catch (Throwable t) {
            plugin.getLogger().fine("Replay frame failed (" + f.kind + "): " + t.getMessage());
        }
    }

    private void sendTabAdd() {
        UserProfile profile = new UserProfile(npcUuid, npcName);
        WrapperPlayServerPlayerInfoUpdate.PlayerInfo info =
                new WrapperPlayServerPlayerInfoUpdate.PlayerInfo(
                        profile, true, 0, GameMode.SURVIVAL, null, null);
        EnumSet<WrapperPlayServerPlayerInfoUpdate.Action> actions = EnumSet.of(
                WrapperPlayServerPlayerInfoUpdate.Action.ADD_PLAYER,
                WrapperPlayServerPlayerInfoUpdate.Action.UPDATE_LISTED);
        send(new WrapperPlayServerPlayerInfoUpdate(actions, List.of(info)));
    }

    private void sendSpawnEntity(ReplayFrame spawn) {
        send(new WrapperPlayServerSpawnEntity(
                entityId, npcUuid, EntityTypes.PLAYER,
                new Location(spawn.x, spawn.y, spawn.z, spawn.yaw, spawn.pitch),
                spawn.yaw, 0,
                new Vector3d(0.0, 0.0, 0.0)));
    }

    /**
     * Default flag byte (no fire / not crouched / not sprinting / not invisible).
     * Cosmetic — replay still works without it on PacketEvents variants where
     * the EntityData ctor signature differs.
     */
    private void sendMetadataVisible() {
        try {
            send(new WrapperPlayServerEntityMetadata(entityId, List.of(
                    new EntityData(0, EntityDataTypes.BYTE, (byte) 0))));
        } catch (Throwable ignored) { }
    }

    private void send(PacketWrapper<?> w) {
        PacketEvents.getAPI().getPlayerManager().sendPacket(viewer, w);
    }
}
