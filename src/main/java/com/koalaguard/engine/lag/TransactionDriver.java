package com.koalaguard.engine.lag;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPing;
import com.koalaguard.KoalaGuard;
import com.koalaguard.data.PlayerData;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Stamps every player with a transaction (Ping) each server tick. The echoed
 * Pong — handled in the capture listener — feeds {@link LagCompensator} (the
 * rolling-median RTT) and advances {@code confirmedTransactions}, which is the
 * authoritative tick clock TimerCheck compares against (server lag can never
 * be mistaken for a client timer because both sides use this same clock).
 */
public final class TransactionDriver extends BukkitRunnable {

    private final KoalaGuard plugin;
    private final AtomicInteger nextId = new AtomicInteger(1);

    public TransactionDriver(KoalaGuard plugin) {
        this.plugin = plugin;
    }

    @Override
    public void run() {
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            PlayerData d = plugin.getDataManager().get(player);
            if (d == null) continue;

            d.serverTicks++;

            long nowNanos = System.nanoTime();
            d.pendingTransactions.values().removeIf(
                    sent -> (nowNanos - sent) / 1_000_000L > 5000);
            if (d.pendingTransactions.size() > 40) continue;   // client not answering

            int id = nextId.getAndIncrement();
            if (id > 100_000_000) nextId.set(1);
            d.pendingTransactions.put(id, System.nanoTime());
            d.lastTransactionSentMs = System.currentTimeMillis();
            try {
                PacketEvents.getAPI().getPlayerManager()
                        .sendPacket(player, new WrapperPlayServerPing(id));
            } catch (Throwable ignored) {
                d.pendingTransactions.remove(id);
            }
        }
    }
}
