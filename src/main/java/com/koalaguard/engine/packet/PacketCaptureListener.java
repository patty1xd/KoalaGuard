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
import com.koalaguard.engine.replay.ReplayFrame;
import com.koalaguard.engine.replay.ReplayKind;
import com.koalaguard.engine.replay.ReplayManager;
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
        ReplayManager replay = plugin.getReplayManager();

        long nanos = System.nanoTime();
        var type = event.getPacketType();

        if (type == PacketType.Play.Client.PLAYER_FLYING
                || type == PacketType.Play.Client.PLAYER_POSITION
                || type == PacketType.Play.Client.PLAYER_ROTATION
                || type == PacketType.Play.Client.PLAYER_POSITION_AND_ROTATION) {
            WrapperPlayClientPlayerFlying w = new WrapperPlayClientPlayerFlying(event);

            // Client-clock accounting for TimerCheck: counted BEFORE any
            // sanity/cancel logic — the rate of what the client SENT is the
            // signal, regardless of what the pipeline later does with it.
            s.movePacketCount.incrementAndGet();

            // ── Protocol sanity — reject what a vanilla client cannot emit ──
            // NaN/Infinity coordinates or rotation feed straight into server
            // AABB/raytrace math (crash/exploit packets); |pitch| beyond ±90
            // is the derp/illegal-rotation fingerprint (vanilla clamps before
            // send); coordinates past ±3.0e7 are beyond the hard world limit.
            // The packet is cancelled HERE so Mojang's handler never sees it,
            // and the stamp is consumed by BadPacketsSanity on the main
            // thread. Yaw is deliberately NOT range-checked: the vanilla
            // client accumulates yaw unbounded (spinning passes ±360°) — only
            // non-finite yaw is illegal.
            String illegal = null;
            if (w.hasPositionChanged()) {
                var pos = w.getLocation().getPosition();
                double px = pos.getX(), py = pos.getY(), pz = pos.getZ();
                if (!Double.isFinite(px) || !Double.isFinite(py) || !Double.isFinite(pz)) {
                    illegal = "non-finite position";
                } else if (Math.abs(px) > 3.0E7 || Math.abs(pz) > 3.0E7
                        || Math.abs(py) > 2.0E7) {
                    illegal = String.format("position out of world bounds (%.3g %.3g %.3g)",
                            px, py, pz);
                }
            }
            if (illegal == null && w.hasRotationChanged()) {
                float ryaw = w.getLocation().getYaw();
                float rpitch = w.getLocation().getPitch();
                if (!Float.isFinite(ryaw) || !Float.isFinite(rpitch)) {
                    illegal = "non-finite rotation";
                } else if (Math.abs(rpitch) > 90.01f) {
                    illegal = String.format("pitch out of range (%.2f)", rpitch);
                }
            }
            if (illegal != null) {
                event.setCancelled(true);
                d.sanityDetail = illegal;
                d.sanitySeq++;
                return;
            }

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

            // Meteor ClickTp fingerprint: it sends N "StatusOnly(true,true)"
            // packets (no pos, no rot, just onGround flags) in rapid succession
            // before the big position packet. Vanilla emits one PLAYER_FLYING
            // per tick (~50ms apart); a burst of ≥3 StatusOnly within <40ms
            // is the literal pattern from Meteor's ClickTPCommand.java.
            boolean statusOnly = !p.hasPos && !p.hasRot;
            if (statusOnly) {
                long lastNs = LAST_STATUS_NANOS.getOrDefault(uuid, 0L);
                long gapNs = nanos - lastNs;
                int run = STATUS_BURST.getOrDefault(uuid, 0);
                if (gapNs < 40_000_000L) {                 // <40ms since previous
                    run++;
                    if (run >= 3) {                        // 3+ in a row inside 40ms each
                        d.clickTpBurstSeq++;
                        d.clickTpBurstSize = run;
                    }
                } else {
                    run = 1;
                }
                STATUS_BURST.put(uuid, run);
                LAST_STATUS_NANOS.put(uuid, nanos);
            } else if (p.hasPos || p.hasRot) {
                STATUS_BURST.remove(uuid);                 // any real movement resets
                LAST_STATUS_NANOS.remove(uuid);
            }

            offer(s, p);
            if (replay != null && (p.hasPos || p.hasRot)) {
                ReplayKind rk = p.hasPos && p.hasRot ? ReplayKind.MOVE
                        : p.hasPos ? ReplayKind.POS : ReplayKind.ROTATE;
                ReplayFrame rf = new ReplayFrame(nanos, rk);
                rf.x = p.x; rf.y = p.y; rf.z = p.z;
                rf.yaw = p.yaw; rf.pitch = p.pitch;
                rf.onGround = p.onGround;
                replay.record(uuid, rf);
            }
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
            if (replay != null) {
                ReplayKind rk = w.getAction() == WrapperPlayClientInteractEntity.InteractAction.ATTACK
                        ? ReplayKind.ATTACK : ReplayKind.ANIMATE;
                ReplayFrame rf = new ReplayFrame(nanos, rk);
                rf.intA = w.getEntityId();
                rf.yaw = s.netYaw; rf.pitch = s.netPitch;
                replay.record(uuid, rf);
            }
            return;
        }

        if (type == PacketType.Play.Client.ANIMATION) {
            CapturedPacket p = new CapturedPacket(seq.getAndIncrement(), PacketKind.ANIMATION, nanos);
            p.yaw = s.netYaw;
            p.pitch = s.netPitch;
            p.hasRot = true;
            offer(s, p);
            if (replay != null) {
                ReplayFrame rf = new ReplayFrame(nanos, ReplayKind.ANIMATE);
                rf.yaw = s.netYaw; rf.pitch = s.netPitch;
                replay.record(uuid, rf);
            }
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
            if (replay != null) {
                ReplayKind rk = switch (w.getAction()) {
                    case START_SPRINTING -> ReplayKind.SPRINT_START;
                    case STOP_SPRINTING  -> ReplayKind.SPRINT_STOP;
                    case START_SNEAKING  -> ReplayKind.SNEAK_START;
                    case STOP_SNEAKING   -> ReplayKind.SNEAK_STOP;
                    default              -> null;
                };
                if (rk != null) replay.record(uuid, new ReplayFrame(nanos, rk));
            }
            return;
        }

        if (type == PacketType.Play.Client.HELD_ITEM_CHANGE) {
            WrapperPlayClientHeldItemChange w = new WrapperPlayClientHeldItemChange(event);
            CapturedPacket p = new CapturedPacket(seq.getAndIncrement(), PacketKind.HELD_ITEM, nanos);
            p.intA = w.getSlot();
            offer(s, p);
            if (replay != null) {
                ReplayFrame rf = new ReplayFrame(nanos, ReplayKind.HELD_ITEM);
                rf.intA = w.getSlot();
                replay.record(uuid, rf);
            }
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
            if (replay != null) {
                ReplayFrame rf = new ReplayFrame(nanos, ReplayKind.DIG);
                rf.x = p.x; rf.y = p.y; rf.z = p.z;
                rf.byteA = (byte) a.ordinal();
                replay.record(uuid, rf);
            }
            return;
        }

        if (type == PacketType.Play.Client.USE_ITEM) {
            s.usingItem = true;
            s.usingItemSinceNanos = nanos;
            offer(s, new CapturedPacket(seq.getAndIncrement(), PacketKind.USE_ITEM, nanos));
            if (replay != null) replay.record(uuid, new ReplayFrame(nanos, ReplayKind.USE_ITEM));
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
            if (replay != null) {
                ReplayFrame rf = new ReplayFrame(nanos, ReplayKind.BLOCK_PLACE);
                rf.x = bp.x; rf.y = bp.y; rf.z = bp.z;
                replay.record(uuid, rf);
            }
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
                // Click type (PICKUP / QUICK_MOVE / DOUBLE_CLICK / ...) — lets
                // BadPacketsDuplicate tell a literal packet resend (identical
                // type) from a vanilla double-click gather, which sends two
                // DIFFERENT-type clicks on the same slot in quick succession.
                p.strA = String.valueOf(w.getWindowClickType());
            } catch (Throwable ignored) { }
            offer(s, p);
            if (replay != null) {
                ReplayFrame rf = new ReplayFrame(nanos, ReplayKind.INV_CLICK);
                rf.intA = p.intA; rf.intB = p.intB;
                replay.record(uuid, rf);
            }
            return;
        }

        if (type == PacketType.Play.Client.CLOSE_WINDOW) {
            offer(s, new CapturedPacket(seq.getAndIncrement(), PacketKind.CLOSE_WINDOW, nanos));
            if (replay != null) replay.record(uuid, new ReplayFrame(nanos, ReplayKind.INV_CLOSE));
            return;
        }

        if (type == PacketType.Play.Client.PLAYER_INPUT) {
            // 1.21.2+ keyboard state — sent by the client whenever the input
            // bitfield CHANGES. Decode is best-effort (wrapper signatures are
            // version-sensitive); on failure nothing is captured and every
            // input-gated check stays dormant for the session.
            try {
                var w = new com.github.retrooper.packetevents.wrapper.play.client
                        .WrapperPlayClientPlayerInput(event);
                int mask = 0;
                if (w.isForward())  mask |= 1;
                if (w.isBackward()) mask |= 2;
                if (w.isLeft())     mask |= 4;
                if (w.isRight())    mask |= 8;
                if (w.isJump())     mask |= 16;
                if (w.isShift())    mask |= 32;
                if (w.isSprint())   mask |= 64;
                s.inputMask = mask;
                s.inputSeen = true;
                s.lastInputNanos = nanos;
                CapturedPacket p = new CapturedPacket(seq.getAndIncrement(), PacketKind.PLAYER_INPUT, nanos);
                p.intA = mask;
                offer(s, p);
            } catch (Throwable ignored) { }
            return;
        }

        if (type == PacketType.Play.Client.TELEPORT_CONFIRM) {
            // Pearl/teleport accept sequence — logged so burst / double-accept
            // fingerprints can be read off the unified stream.
            CapturedPacket p = new CapturedPacket(seq.getAndIncrement(), PacketKind.TELEPORT_CONFIRM, nanos);
            try {
                var w = new com.github.retrooper.packetevents.wrapper.play.client
                        .WrapperPlayClientTeleportConfirm(event);
                p.intA = w.getTeleportId();
            } catch (Throwable ignored) { }
            offer(s, p);
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
                        // Netty-thread DoS cap (CWE-400): a real brand is tens
                        // of bytes; never decode + regex more than 256.
                        int blen = Math.min(data.length, 256);
                        String brand = new String(data, 0, blen, StandardCharsets.UTF_8)
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
                            // Brand-vs-modloader-channel CONTRADICTION:
                            // A cheat client commonly lies about its brand
                            // (sends "vanilla") to dodge brand fingerprinting,
                            // but still registers Fabric/Forge plugin channels
                            // because the underlying mod loader does so itself.
                            // A genuine vanilla client cannot register modded
                            // channels — the loader doesn't exist. Set the
                            // contradiction flag for CheatClientCheck.
                            d.brandVanilla = lc.equals("vanilla");
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
                    // (NUL/space separated). contains() over the blob is
                    // enough — separators do not matter for a substring match.
                    // Netty-thread DoS cap (CWE-400): a flooded 32 KiB payload
                    // × ~45 cheat ids × 14 loader channels was an O(n·k)
                    // amplification on the hot path. 8 KiB is far beyond any
                    // legitimate register list (even heavy modpacks ≈ 2-3 KiB);
                    // a cheat channel hiding past the cap is caught the next
                    // time it actually TALKS on that channel (the matchCheat
                    // on lcCh above has no payload involved).
                    int rlen = Math.min(data.length, 8192);
                    String reg = new String(data, 0, rlen, StandardCharsets.UTF_8).toLowerCase();
                    String regHit = matchCheat(reg);
                    if (regHit != null) {
                        d.flagBadChannel = true;
                        d.badChannel = regHit;
                    }
                    // Brand="vanilla" + modded channels = cheat-client tell.
                    // Genuine vanilla cannot register Fabric/Forge channels
                    // because no mod loader is loaded. A cheat that lies
                    // about its brand to dodge fingerprinting still has the
                    // mod loader running underneath, which leaks its channels.
                    if (d.brandVanilla) {
                        for (String modCh : MOD_LOADER_CHANNELS) {
                            if (reg.contains(modCh)) {
                                d.flagBadChannel = true;
                                d.badChannel = "vanilla-brand+modded-channel:" + modCh;
                                break;
                            }
                        }
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
            // free clients. Substring match — so every entry must be specific
            // enough to never appear inside a LEGIT mod's channel id. Bare
            // "lambda" was removed: it substring-matched LambdaControls /
            // LambdaBetterGrass ("lambdacontrols:...") — legitimate, popular
            // Fabric mods by LambdAurora — and branded their users cheaters.
            // The KAMI-fork cheat namespace is still covered by
            // "lambda-client" / "kamiblue" / "kami-blue".
            "skidbounce", "opai-client", "opaiclient", "lambda-client",
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

    /**
     * Mod-loader plugin-channel substrings. ANY player whose brand is
     * "vanilla" but who registers a channel containing one of these is
     * lying — a genuine vanilla client has no mod loader and cannot
     * register loader-defined channels.
     */
    private static final String[] MOD_LOADER_CHANNELS = {
            "fabric", "fml", "forge", "fabric-screen-handler",
            "fabric-api", "fabric:registry-sync", "forge:tier_sorting",
            "litematica", "minihud", "tweakeroo", "malilib",
            "quilt", "neoforge", "carpet"
    };

    /** Per-player intake-overflow counters (visible via /kg debug). */
    private static final java.util.concurrent.ConcurrentHashMap<java.util.UUID, long[]> DROPS
            = new java.util.concurrent.ConcurrentHashMap<>();
    /** Per-player StatusOnly burst tracking — Meteor ClickTp fingerprint. */
    private static final java.util.concurrent.ConcurrentHashMap<java.util.UUID, Integer> STATUS_BURST
            = new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.concurrent.ConcurrentHashMap<java.util.UUID, Long> LAST_STATUS_NANOS
            = new java.util.concurrent.ConcurrentHashMap<>();

    private void offer(PlayerState s, CapturedPacket p) {
        // Silent drop is a DoS vector — a cheater can flood movement packets to
        // fill the queue and silently disable detection on aux packets it cares
        // about (attacks, totem moves). To address this:
        //   1. If we would overflow, PREFER to drop a MOVEMENT packet over the
        //      one we're trying to enqueue (so aux packets get priority).
        //   2. Counters per player so /kg debug surfaces the flood and admins
        //      can see when a player is overrunning the queue.
        if (s.intake.size() < 4096) { s.intake.offer(p); return; }
        long[] c = DROPS.computeIfAbsent(s.uuid, k -> new long[2]);
        if (p.kind != PacketKind.MOVEMENT) {
            // Auxiliary packet under pressure — try to evict ONE movement
            // packet at the head of the queue to make room. ConcurrentLinkedQueue
            // has no remove-by-predicate so we peek+drain a few until we hit
            // movement or fall back to dropping the new packet.
            int probes = 0;
            for (CapturedPacket head; probes++ < 8 && (head = s.intake.peek()) != null; ) {
                if (head.kind == PacketKind.MOVEMENT) {
                    s.intake.poll();
                    c[0]++;                                    // movement evictions
                    s.intake.offer(p);
                    return;
                }
                break; // head is also aux — keep it, drop the incoming.
            }
        }
        c[1]++;                                                // total drops
    }

    /** Read-only snapshot of [movementEvictions, totalDrops] for telemetry. */
    public static long[] getDropCounters(java.util.UUID id) {
        long[] c = DROPS.get(id);
        return c == null ? new long[]{0, 0} : new long[]{ c[0], c[1] };
    }
}
