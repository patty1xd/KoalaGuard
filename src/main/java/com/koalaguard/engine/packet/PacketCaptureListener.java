package com.koalaguard.engine.packet;

import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.DiggingAction;
import com.github.retrooper.packetevents.protocol.player.User;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientEntityAction;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientHeldItemChange;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientInteractEntity;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerDigging;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerFlying;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPluginMessage;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPong;
import com.koalaguard.KoalaGuard;
import com.koalaguard.data.PlayerData;
import com.koalaguard.engine.state.PlayerState;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * THE unified capture layer. One listener, one ordered log, every reconstructable
 * packet. It runs on the netty thread and does NOTHING but decode ground truth
 * into {@link CapturedPacket}s and hand them to the main-thread engine via the
 * lock-free intake queue. No Bukkit calls, no check logic, no timing decisions.
 */
public final class PacketCaptureListener extends PacketListenerAbstract {

    private final KoalaGuard plugin;
    private final AtomicLong seq = new AtomicLong();

    public PacketCaptureListener(KoalaGuard plugin) {
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
        PlayerState s = d.engine;

        long nanos = System.nanoTime();
        var type = event.getPacketType();

        if (type == PacketType.Play.Client.PLAYER_FLYING
                || type == PacketType.Play.Client.PLAYER_POSITION
                || type == PacketType.Play.Client.PLAYER_ROTATION
                || type == PacketType.Play.Client.PLAYER_POSITION_AND_ROTATION) {
            WrapperPlayClientPlayerFlying w = new WrapperPlayClientPlayerFlying(event);
            CapturedPacket p = new CapturedPacket(seq.getAndIncrement(), PacketKind.MOVEMENT, nanos);
            p.onGround = w.isOnGround();
            if (w.hasRotationChanged()) {
                p.hasRot = true;
                p.yaw = w.getLocation().getYaw();
                p.pitch = w.getLocation().getPitch();
            }
            if (w.hasPositionChanged()) {
                p.hasPos = true;
                var pos = w.getLocation().getPosition();
                p.x = pos.getX(); p.y = pos.getY(); p.z = pos.getZ();
            }
            offer(s, p);
            return;
        }

        if (type == PacketType.Play.Client.INTERACT_ENTITY) {
            WrapperPlayClientInteractEntity w = new WrapperPlayClientInteractEntity(event);
            CapturedPacket p = new CapturedPacket(seq.getAndIncrement(), PacketKind.INTERACT_ENTITY, nanos);
            p.intA = w.getEntityId();
            p.objA = w.getAction();   // InteractAction enum
            // Silent combat cancellation: a confirmed persistent combat
            // violation drops the attack so it never deals damage/knockback.
            if (w.getAction() == WrapperPlayClientInteractEntity.InteractAction.ATTACK
                    && System.currentTimeMillis() < d.combatCancelUntilMs) {
                event.setCancelled(true);
            }
            offer(s, p);          // still recorded so detection keeps running
            return;
        }

        if (type == PacketType.Play.Client.ANIMATION) {
            offer(s, new CapturedPacket(seq.getAndIncrement(), PacketKind.ANIMATION, nanos));
            return;
        }

        if (type == PacketType.Play.Client.ENTITY_ACTION) {
            WrapperPlayClientEntityAction w = new WrapperPlayClientEntityAction(event);
            switch (w.getAction()) {
                case START_SPRINTING -> s.sprinting = true;
                case STOP_SPRINTING  -> s.sprinting = false;
                case START_SNEAKING  -> s.sneaking = true;
                case STOP_SNEAKING   -> s.sneaking = false;
                default -> { }
            }
            CapturedPacket p = new CapturedPacket(seq.getAndIncrement(), PacketKind.ENTITY_ACTION, nanos);
            p.strA = w.getAction().name();
            offer(s, p);
            return;
        }

        if (type == PacketType.Play.Client.HELD_ITEM_CHANGE) {
            WrapperPlayClientHeldItemChange w = new WrapperPlayClientHeldItemChange(event);
            CapturedPacket p = new CapturedPacket(seq.getAndIncrement(), PacketKind.HELD_ITEM, nanos);
            p.intA = w.getSlot();
            offer(s, p);
            return;
        }

        if (type == PacketType.Play.Client.PLAYER_DIGGING) {
            WrapperPlayClientPlayerDigging w = new WrapperPlayClientPlayerDigging(event);
            DiggingAction a = w.getAction();
            if (a == DiggingAction.RELEASE_USE_ITEM) s.usingItem = false;
            CapturedPacket p = new CapturedPacket(seq.getAndIncrement(), PacketKind.DIGGING, nanos);
            p.strA = a.name();
            offer(s, p);
            return;
        }

        if (type == PacketType.Play.Client.USE_ITEM) {
            s.usingItem = true;
            s.usingItemSinceNanos = nanos;
            offer(s, new CapturedPacket(seq.getAndIncrement(), PacketKind.USE_ITEM, nanos));
            return;
        }

        if (type == PacketType.Play.Client.PLAYER_BLOCK_PLACEMENT) {
            offer(s, new CapturedPacket(seq.getAndIncrement(), PacketKind.BLOCK_PLACE, nanos));
            return;
        }

        if (type == PacketType.Play.Client.CLICK_WINDOW) {
            // The chain validators only need that a window-click occurred in
            // the stream — the slot/window decode is intentionally omitted to
            // stay version-robust across PacketEvents wrapper changes.
            offer(s, new CapturedPacket(seq.getAndIncrement(), PacketKind.CLICK_WINDOW, nanos));
            return;
        }

        if (type == PacketType.Play.Client.CLOSE_WINDOW) {
            offer(s, new CapturedPacket(seq.getAndIncrement(), PacketKind.CLOSE_WINDOW, nanos));
            return;
        }

        if (type == PacketType.Play.Client.PONG) {
            WrapperPlayClientPong pong = new WrapperPlayClientPong(event);
            Long sent = d.pendingTransactions.remove(pong.getId());
            if (sent != null) {
                int rtt = (int) ((System.nanoTime() - sent) / 1_000_000L);
                d.transactionPing = rtt;
                d.lastPongMs = System.currentTimeMillis();
                d.confirmedTransactions++;
                d.lag.onTransaction(rtt);
            }
            CapturedPacket p = new CapturedPacket(seq.getAndIncrement(), PacketKind.PONG, nanos);
            p.intA = pong.getId();
            offer(s, p);
            return;
        }

        if (type == PacketType.Play.Client.PLUGIN_MESSAGE) {
            try {
                WrapperPlayClientPluginMessage w = new WrapperPlayClientPluginMessage(event);
                String ch = w.getChannelName();
                if (ch != null && (ch.equalsIgnoreCase("minecraft:brand") || ch.equalsIgnoreCase("MC|Brand"))) {
                    byte[] data = w.getData();
                    if (data != null && data.length > 0) {
                        String brand = new String(data, StandardCharsets.UTF_8)
                                .replaceAll("[^\\x20-\\x7E]", "").trim();
                        if (!brand.isEmpty()) {
                            d.packetBrand = brand;
                            d.clientBrand = brand;
                            String lc = brand.toLowerCase();
                            if (lc.contains("autototem") || lc.contains("totemmod")
                                    || lc.contains("xeltotem")) {
                                d.flagBadBrand = true;
                            }
                        }
                    }
                }
            } catch (Throwable ignored) { }
        }
    }

    private void offer(PlayerState s, CapturedPacket p) {
        if (s.intake.size() < 4096) s.intake.offer(p);
    }
}
