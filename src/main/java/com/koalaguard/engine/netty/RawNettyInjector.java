package com.koalaguard.engine.netty;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.protocol.player.User;
import com.koalaguard.KoalaGuard;
import com.koalaguard.data.PlayerData;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import org.bukkit.entity.Player;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Raw netty channel-handler injection BELOW PacketEvents.
 *
 * PacketEvents already runs on the netty thread and decodes typed wrappers,
 * but it operates after vanilla decoding. This layer sits one notch deeper:
 * a {@link RawPacketSniffer} is inserted directly before the PacketEvents
 * handler in each player's pipeline, observing every inbound frame as raw
 * {@link io.netty.buffer.ByteBuf} bytes. That gives us byte-level signals
 * (frame length distribution, queue depth, inter-frame nanos, oversized
 * packet detection) that PacketEvents' typed surface cannot expose.
 *
 * The handler is read-only — it never consumes the buffer, only reads its
 * {@code readableBytes()} and a peek of the first few bytes via
 * {@code getBytes(idx, dst)} which does NOT advance {@code readerIndex}, so
 * the full frame still arrives at PacketEvents and Mojang unchanged.
 *
 * Compatibility:
 *   • Uses the User.getChannel() API to get the netty Channel — same path
 *     PacketEvents uses internally, no reflection.
 *   • Idempotent inject/uninject: a re-inject for the same player removes
 *     the previous handler first; uninject is null-safe.
 *   • Bail-tolerant: if the channel pipeline doesn't have the expected
 *     anchor ("packet_handler"), we add at the end instead of throwing.
 */
public final class RawNettyInjector {

    private static final String HANDLER_NAME = "koalaguard_raw_sniffer";

    private final KoalaGuard plugin;
    private final ConcurrentHashMap<UUID, AtomicReference<Channel>> tracked
            = new ConcurrentHashMap<>();

    public RawNettyInjector(KoalaGuard plugin) {
        this.plugin = plugin;
    }

    public void inject(Player player) {
        if (player == null) return;
        try {
            User user = PacketEvents.getAPI().getPlayerManager().getUser(player);
            if (user == null) return;
            Object chObj = user.getChannel();
            if (!(chObj instanceof Channel ch)) return;
            if (!ch.isOpen()) return;
            PlayerData data = plugin.getDataManager().get(player);
            if (data == null) return;

            // Idempotent: remove any existing handler first.
            ChannelHandler existing = ch.pipeline().get(HANDLER_NAME);
            if (existing != null) ch.pipeline().remove(existing);

            RawPacketSniffer sniffer = new RawPacketSniffer(plugin, data, player);
            // packet_handler is Mojang's NetworkManager handler; PacketEvents
            // installs its own handler that sits AT or NEAR packet_handler.
            // Inserting before it guarantees we see the frame first AND that
            // bumping packet_handler's position doesn't unanchor us.
            try {
                ch.pipeline().addBefore("packet_handler", HANDLER_NAME, sniffer);
            } catch (java.util.NoSuchElementException nse) {
                // Some forks rename the anchor — fall back to a tail-add so
                // we still observe traffic. We lose pre-decode read position,
                // but the byte-level frame metrics still work.
                ch.pipeline().addLast(HANDLER_NAME, sniffer);
            } catch (IllegalArgumentException dup) {
                // Race: someone else just added the same name. Tolerated.
            }
            tracked.put(player.getUniqueId(), new AtomicReference<>(ch));
        } catch (Throwable t) {
            plugin.getLogger().fine("[netty-inject] " + player.getName()
                    + " skipped: " + t);
        }
    }

    public void uninject(UUID uuid) {
        if (uuid == null) return;
        AtomicReference<Channel> ref = tracked.remove(uuid);
        if (ref == null) return;
        Channel ch = ref.get();
        if (ch == null || !ch.isOpen()) return;
        try {
            ch.eventLoop().execute(() -> {
                try {
                    ChannelHandler h = ch.pipeline().get(HANDLER_NAME);
                    if (h != null) ch.pipeline().remove(h);
                } catch (Throwable ignored) { }
            });
        } catch (Throwable ignored) { }
    }

    public void uninjectAll() {
        for (UUID uuid : new java.util.ArrayList<>(tracked.keySet())) {
            uninject(uuid);
        }
    }
}
