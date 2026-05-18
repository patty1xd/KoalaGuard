package com.koalaguard.engine.honeypot;

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
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityMetadata;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerInfoRemove;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerInfoUpdate;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnEntity;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerTeams;
import com.koalaguard.KoalaGuard;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;

import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A packet-level "ghost player" — a player-TYPE entity sent ONLY to one
 * suspect's client. The server never knows it exists; the client puts it in
 * its entity list, so a kill-aura with a strict players-only target filter
 * (the ArmorStand honeypot's blind spot) still attacks it. The inbound
 * InteractEntity is captured by PacketEvents at the netty layer before server
 * logic, so detection (matching the ghost's entity id) works unchanged.
 *
 * Defensive: the exact server-wrapper signatures are version-sensitive; any
 * mismatch is caught, logged once, and simply yields no ghost (the ArmorStand
 * honeypot still runs), so a bad build can never crash a player's session.
 */
public final class FakePlayer {

    /** Dedicated high id range — a real server never reaches ~2e9 entity ids. */
    private static final AtomicInteger ID = new AtomicInteger(2_000_000_000);
    private static volatile boolean warned;

    private FakePlayer() {}

    public static final class Ghost {
        public final int entityId;
        public final UUID uuid;
        public final String team;
        Ghost(int entityId, UUID uuid, String team) {
            this.entityId = entityId; this.uuid = uuid; this.team = team;
        }
    }

    public static Ghost spawn(KoalaGuard plugin, Player viewer,
                              double x, double y, double z, float yaw) {
        try {
            int eid = ID.updateAndGet(v -> v >= 2_100_000_000 ? 2_000_000_000 : v + 1);
            UUID uuid = UUID.randomUUID();
            String name = randomName();
            UserProfile profile = new UserProfile(uuid, name);

            WrapperPlayServerPlayerInfoUpdate.PlayerInfo info =
                    new WrapperPlayServerPlayerInfoUpdate.PlayerInfo(
                            profile, false, 0, GameMode.SURVIVAL, null, null);
            send(viewer, new WrapperPlayServerPlayerInfoUpdate(
                    EnumSet.of(WrapperPlayServerPlayerInfoUpdate.Action.ADD_PLAYER),
                    List.of(info)));

            // Scoreboard team with nametag NEVER → the ghost's name tag is not
            // rendered at all (the only way to hide a player entity's tag).
            send(viewer, new WrapperPlayServerTeams(name,
                    WrapperPlayServerTeams.TeamMode.CREATE,
                    new WrapperPlayServerTeams.ScoreBoardTeamInfo(
                            Component.empty(), null, null,
                            WrapperPlayServerTeams.NameTagVisibility.NEVER,
                            WrapperPlayServerTeams.CollisionRule.NEVER,
                            NamedTextColor.WHITE,
                            WrapperPlayServerTeams.OptionData.NONE),
                    name));

            send(viewer, new WrapperPlayServerSpawnEntity(
                    eid, uuid, EntityTypes.PLAYER,
                    new Location(x, y, z, yaw, 0f), 0f, 0,
                    new Vector3d(0.0, 0.0, 0.0)));

            // Invisible: shared entity-flags byte (index 0), bit 0x20.
            send(viewer, new WrapperPlayServerEntityMetadata(eid, List.of(
                    new EntityData(0, EntityDataTypes.BYTE, (byte) 0x20))));

            // Drop the tab/profile entry but KEEP the entity client-side.
            send(viewer, new WrapperPlayServerPlayerInfoRemove(
                    Collections.singletonList(uuid)));

            return new Ghost(eid, uuid, name);
        } catch (Throwable t) {
            if (!warned) {
                warned = true;
                plugin.getLogger().warning("[honeypot] ghost-player packets "
                        + "unavailable on this build (" + t + ") — falling back "
                        + "to the ArmorStand honeypot only.");
            }
            return null;
        }
    }

    public static void despawn(Player viewer, Ghost g) {
        if (g == null) return;
        try { send(viewer, new WrapperPlayServerDestroyEntities(g.entityId)); }
        catch (Throwable ignored) { }
        try {
            send(viewer, new WrapperPlayServerPlayerInfoRemove(
                    Collections.singletonList(g.uuid)));
        } catch (Throwable ignored) { }
        try {
            send(viewer, new WrapperPlayServerTeams(g.team,
                    WrapperPlayServerTeams.TeamMode.REMOVE,
                    (WrapperPlayServerTeams.ScoreBoardTeamInfo) null));
        } catch (Throwable ignored) { }
    }

    private static void send(Player viewer, PacketWrapper<?> w) {
        PacketEvents.getAPI().getPlayerManager().sendPacket(viewer, w);
    }

    private static String randomName() {
        String hex = Long.toHexString(System.nanoTime());
        return ("KG" + hex).substring(0, Math.min(16, hex.length() + 2));
    }
}
