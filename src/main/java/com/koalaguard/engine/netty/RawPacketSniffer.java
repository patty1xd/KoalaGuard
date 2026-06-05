package com.koalaguard.engine.netty;

import com.koalaguard.KoalaGuard;
import com.koalaguard.data.PlayerData;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import org.bukkit.entity.Player;

/**
 * Read-only netty channel handler injected per player by {@link RawNettyInjector}.
 *
 * Sits ABOVE Mojang's packet decoder so it observes frames that have already
 * been length-prefixed-stripped and decompressed but BEFORE the typed packet
 * decoder runs. Records, per frame:
 *   • receive nanos (netty-thread monotonic),
 *   • raw byte length (the actual on-wire payload size),
 *   • first byte (packet id discriminator).
 *
 * Read-only: uses {@link ByteBuf#getByte(int)} and {@link ByteBuf#readableBytes()}
 * which do NOT advance the readerIndex, so PacketEvents and Mojang both see
 * the full frame intact. Outbound traffic is observed for outgoing-byte-rate
 * stats (no read at all).
 *
 * Stats are aggregated into {@link RawPacketStats} on {@link PlayerData} so
 * a future check can read them tick-side. Nothing here causes flags directly
 * — this is the OBSERVATION layer; detection lives in the engine checks.
 */
public final class RawPacketSniffer extends ChannelDuplexHandler {

    private final KoalaGuard plugin;
    private final PlayerData data;
    private final Player player;

    public RawPacketSniffer(KoalaGuard plugin, PlayerData data, Player player) {
        this.plugin = plugin;
        this.data = data;
        this.player = player;
        if (data.rawStats == null) data.rawStats = new RawPacketStats();
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        try {
            if (msg instanceof ByteBuf buf && buf.isReadable()) {
                int len = buf.readableBytes();
                int rIdx = buf.readerIndex();
                int firstByte = buf.getByte(rIdx) & 0xFF;
                long now = System.nanoTime();
                data.rawStats.recordInbound(now, len, firstByte);
            }
        } catch (Throwable ignored) {
            // Sniffer is non-fatal — any read error falls back to forwarding.
        }
        super.channelRead(ctx, msg);
    }

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
        try {
            if (msg instanceof ByteBuf buf) {
                int len = buf.readableBytes();
                data.rawStats.recordOutbound(System.nanoTime(), len);
            }
        } catch (Throwable ignored) { }
        super.write(ctx, msg, promise);
    }
}
