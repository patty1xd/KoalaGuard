package com.koalaguard.processor;

import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.DiggingAction;
import com.github.retrooper.packetevents.protocol.player.User;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientAnimation;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientEntityAction;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientHeldItemChange;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientInteractEntity;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerDigging;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerFlying;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPluginMessage;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPong;
import com.koalaguard.KoalaGuard;
import com.koalaguard.data.PlayerData;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Runs on the netty packet thread. It ONLY records ground-truth into
 * {@link PlayerData} (thread-safe fields) — no Bukkit API, no check logic.
 * The main-thread evaluators ({@code MovementTask}, {@code CombatProcessor})
 * consume this packet-accurate model, which is what makes the detection
 * comparable to Grim/Vulcan/TotemGuard instead of lossy Bukkit events.
 */
public final class PacketProcessor extends PacketListenerAbstract {

    private final KoalaGuard plugin;

    public PacketProcessor(KoalaGuard plugin) {
        super(PacketListenerPriority.MONITOR);
        this.plugin = plugin;
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        User user = event.getUser();
        if (user == null || user.getUUID() == null) return;
        UUID uuid = user.getUUID();
        PlayerData d = plugin.getDataManager().get(uuid);
        if (d == null) return;

        long now = System.currentTimeMillis();
        var type = event.getPacketType();

        if (type == PacketType.Play.Client.PLAYER_FLYING
                || type == PacketType.Play.Client.PLAYER_POSITION
                || type == PacketType.Play.Client.PLAYER_ROTATION
                || type == PacketType.Play.Client.PLAYER_POSITION_AND_ROTATION) {
            WrapperPlayClientPlayerFlying w = new WrapperPlayClientPlayerFlying(event);
            d.flyingPacketCount++;
            d.flyingTimes.addLast(now);
            while (!d.flyingTimes.isEmpty() && now - d.flyingTimes.peekFirst() > 4000) d.flyingTimes.pollFirst();
            d.pOnGround = w.isOnGround();
            if (w.hasRotationChanged()) {
                float yaw = w.getLocation().getYaw();
                float pitch = w.getLocation().getPitch();
                d.pYaw = yaw; d.pPitch = pitch; d.pHasRot = true;
                d.pushRotation(yaw, pitch);
            }
            if (w.hasPositionChanged()) {
                var pos = w.getLocation().getPosition();
                d.pX = pos.getX(); d.pY = pos.getY(); d.pZ = pos.getZ();
                d.pHasPos = true;
                // One frame == one client movement tick (no server-tick aliasing).
                if (d.moveQueue.size() < 100) {
                    d.moveQueue.add(new double[]{
                            pos.getX(), pos.getY(), pos.getZ(),
                            d.pYaw, d.pPitch, w.isOnGround() ? 1.0 : 0.0});
                }
            }
            return;
        }

        if (type == PacketType.Play.Client.INTERACT_ENTITY) {
            WrapperPlayClientInteractEntity w = new WrapperPlayClientInteractEntity(event);
            if (w.getAction() == WrapperPlayClientInteractEntity.InteractAction.ATTACK) {
                d.lastAttackPacketMs = now;
                d.attackPacketTimes.addLast(now);
                while (!d.attackPacketTimes.isEmpty() && now - d.attackPacketTimes.peekFirst() > 5000)
                    d.attackPacketTimes.pollFirst();
                d.markAttacked(w.getEntityId());
            }
            return;
        }

        if (type == PacketType.Play.Client.ANIMATION) {
            new WrapperPlayClientAnimation(event); // consume/validate
            d.lastSwingMs = now;
            d.swingTimes.addLast(now);
            while (!d.swingTimes.isEmpty() && now - d.swingTimes.peekFirst() > 5000)
                d.swingTimes.pollFirst();
            return;
        }

        if (type == PacketType.Play.Client.ENTITY_ACTION) {
            WrapperPlayClientEntityAction w = new WrapperPlayClientEntityAction(event);
            switch (w.getAction()) {
                case START_SPRINTING -> d.sprinting = true;
                case STOP_SPRINTING  -> d.sprinting = false;
                case START_SNEAKING  -> d.sneaking = true;
                case STOP_SNEAKING   -> d.sneaking = false;
                default -> { }
            }
            return;
        }

        if (type == PacketType.Play.Client.HELD_ITEM_CHANGE) {
            WrapperPlayClientHeldItemChange w = new WrapperPlayClientHeldItemChange(event);
            d.lastHeldSlot = w.getSlot();
            d.lastHeldSlotMs = now;
            d.lastSlotChangeMs = now;
            return;
        }

        if (type == PacketType.Play.Client.PLAYER_DIGGING) {
            WrapperPlayClientPlayerDigging w = new WrapperPlayClientPlayerDigging(event);
            DiggingAction a = w.getAction();
            if (a == DiggingAction.RELEASE_USE_ITEM) { d.usingItem = false; }
            else if (a == DiggingAction.SWAP_ITEM_WITH_OFFHAND) { d.lastOffhandSwapMs = now; }
            else if (a == DiggingAction.START_DIGGING) { d.lastDiggingStartMs = now; }
            return;
        }

        if (type == PacketType.Play.Client.USE_ITEM) {
            d.usingItem = true;
            d.useStartMs = now;
            return;
        }

        if (type == PacketType.Play.Client.CLICK_WINDOW) {
            d.lastClickWindowMs = now;
            return;
        }

        if (type == PacketType.Play.Client.PONG) {
            WrapperPlayClientPong pong = new WrapperPlayClientPong(event);
            plugin.getTransactionManager().onPong(uuid, pong.getId());
            return;
        }

        if (type == PacketType.Play.Client.PLUGIN_MESSAGE) {
            try {
                WrapperPlayClientPluginMessage w = new WrapperPlayClientPluginMessage(event);
                String ch = w.getChannelName();
                if (ch != null && (ch.equalsIgnoreCase("minecraft:brand") || ch.equalsIgnoreCase("MC|Brand"))) {
                    byte[] data = w.getData();
                    if (data != null && data.length > 0) {
                        String brand = new String(data, StandardCharsets.UTF_8).replaceAll("[^\\x20-\\x7E]", "").trim();
                        if (!brand.isEmpty()) {
                            d.packetBrand = brand;
                            d.clientBrand = brand;
                            String lc = brand.toLowerCase();
                            if (lc.contains("autototem") || lc.contains("totemmod") || lc.contains("xeltotem")) {
                                d.flagBadBrand = true;
                            }
                        }
                    }
                }
            } catch (Throwable ignored) { }
        }
    }
}
