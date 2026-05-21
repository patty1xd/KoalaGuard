package com.koalaguard.engine.packet;

import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.DiggingAction;
import com.github.retrooper.packetevents.protocol.player.User;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientClickWindow;
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
            // Movement-cancel window (set right after a setback): drop the
            // position component so cheat packets already in the netty pipe
            // cannot immediately undo the rubber-band. Rotation is left to
            // pass — we don't want to lock the player's camera, and rotation
            // alone can't take them off the setback anchor.
            boolean dropPos = System.currentTimeMillis() < d.movementCancelUntilMs;
            CapturedPacket p = new CapturedPacket(seq.getAndIncrement(), PacketKind.MOVEMENT, nanos);
            p.onGround = w.isOnGround();
            if (w.hasRotationChanged()) {
                p.hasRot = true;
                p.yaw = w.getLocation().getYaw();
                p.pitch = w.getLocation().getPitch();
                s.netYaw = p.yaw;
                s.netPitch = p.pitch;
            }
            if (w.hasPositionChanged() && !dropPos) {
                p.hasPos = true;
                var pos = w.getLocation().getPosition();
                p.x = pos.getX(); p.y = pos.getY(); p.z = pos.getZ();
            }
            if (dropPos && w.hasPositionChanged()) {
                event.setCancelled(true);                 // Mojang never sees it
            }
            offer(s, p);
            return;
        }

        if (type == PacketType.Play.Client.INTERACT_ENTITY) {
            WrapperPlayClientInteractEntity w = new WrapperPlayClientInteractEntity(event);
            CapturedPacket p = new CapturedPacket(seq.getAndIncrement(), PacketKind.INTERACT_ENTITY, nanos);
            p.intA = w.getEntityId();
            p.objA = w.getAction();   // InteractAction enum
            // Stamp the true client rotation at attack-send time. The frame's
            // yaw/pitch is FROZEN while a player only sends rotation-only +
            // attack packets (no position frame is pushed), so aim checks that
            // read the frame would see a stale rotation for stationary attacks.
            p.yaw = s.netYaw;
            p.pitch = s.netPitch;
            p.hasRot = true;
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
            CapturedPacket p = new CapturedPacket(seq.getAndIncrement(), PacketKind.ANIMATION, nanos);
            p.yaw = s.netYaw;
            p.pitch = s.netPitch;
            p.hasRot = true;
            offer(s, p);
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
            // Block position is needed by FastBreak to match START with FINISH.
            try {
                var pos = w.getBlockPosition();
                if (pos != null) {
                    p.x = pos.getX(); p.y = pos.getY(); p.z = pos.getZ();
                    p.hasPos = true;
                }
            } catch (Throwable ignored) { }
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
            CapturedPacket bp = new CapturedPacket(seq.getAndIncrement(), PacketKind.BLOCK_PLACE, nanos);
            try {
                var w = new com.github.retrooper.packetevents.wrapper.play.client
                        .WrapperPlayClientPlayerBlockPlacement(event);
                var pos = w.getBlockPosition();          // clicked block
                bp.x = pos.getX(); bp.y = pos.getY(); bp.z = pos.getZ();
                bp.strA = String.valueOf(w.getFace());   // BlockFace toward placement
                bp.hasPos = true;
            } catch (Throwable ignored) { }
            // True client look at place time (block-place packets carry none).
            bp.yaw = s.netYaw;
            bp.pitch = s.netPitch;
            bp.hasRot = true;
            offer(s, bp);
            return;
        }

        if (type == PacketType.Play.Client.CLICK_WINDOW) {
            CapturedPacket p = new CapturedPacket(seq.getAndIncrement(), PacketKind.CLICK_WINDOW, nanos);
            // Decode is best-effort: the cluster / combat-concurrent signals
            // only need that a window-click occurred, so a wrapper change on a
            // given MC version can NEVER silence AutoTotem detection.
            try {
                WrapperPlayClientClickWindow w = new WrapperPlayClientClickWindow(event);
                p.intA = w.getSlot();       // 45 = off-hand (Meteor target)
                p.intB = w.getWindowId();   // 0  = player inventory menu
            } catch (Throwable ignored) { }
            offer(s, p);
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
                if (ch == null) return;
                String lcCh = ch.toLowerCase();
                byte[] data = w.getData();

                if (lcCh.equals("minecraft:brand") || lcCh.equals("mc|brand")) {
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
                            String hit = matchCheat(lc);
                            if (hit != null) {
                                d.flagBadChannel = true;
                                d.badChannel = "brand:" + hit;
                            }
                        }
                    }
                    return;
                }

                // Layer 1 — cheat-client plugin channel fingerprint. The
                // channel a client registers / talks on is a near-zero-FP
                // identifier; vanilla and legit mods never use these.
                String chHit = matchCheat(lcCh);
                if (chHit != null) {
                    d.flagBadChannel = true;
                    d.badChannel = ch;
                } else if ((lcCh.equals("minecraft:register") || lcCh.equals("register"))
                        && data != null && data.length > 0) {
                    // Payload is a list of channel names being registered
                    // (NUL/space separated). contains() over the whole blob is
                    // enough — separators do not matter for a substring match.
                    String reg = new String(data, StandardCharsets.UTF_8).toLowerCase();
                    String regHit = matchCheat(reg);
                    if (regHit != null) {
                        d.flagBadChannel = true;
                        d.badChannel = regHit;
                    }
                }
            } catch (Throwable ignored) { }
        }
    }

    /** Unambiguous cheat-client channel/brand identifiers (near-zero FP). */
    private static final String[] CHEAT_IDS = {
            "meteor", "wurst", "liquidbounce", "aristois", "rusherhack",
            "impactclient", "future-client", "futureclient",
            "salhack", "kamiblue", "kami-blue", "wwe-client", "sigma-client",
            "novoline", "doomsday-client", "inertia-client", "rise-client",
            "nodus", "huzuni", "flux-client", "vapeclient", "vape-client",
            "entropy-client", "raven-b4",
            // 2026 additions — observed brand/channel strings across paid +
            // free clients. Substring match, so e.g. "lambda" catches every
            // KAMI fork that kept the namespace.
            "skidbounce", "opai-client", "opaiclient", "lambda-client", "lambda",
            "sentience", "lifeware", "jello-client", "holyworld",
            "dolphin-client", "polar-client", "sigma5", "sigma-5",
            "fdp-client", "fdpclient", "cresent", "matrix-client",
            "myau-client", "vape-v4", "memestware", "ratclient",
            "celestial-client", "moonlight-client"
    };

    private static String matchCheat(String s) {
        if (s == null || s.isEmpty()) return null;
        for (String id : CHEAT_IDS) if (s.contains(id)) return id;
        return null;
    }

    private void offer(PlayerState s, CapturedPacket p) {
        if (s.intake.size() < 4096) s.intake.offer(p);
    }
}
