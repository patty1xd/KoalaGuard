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
 * rolling-median RTT) and advances {@code confirmedTransactions}, the
 * authoritative server-side tick clock used for lag compensation.
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

            // Adaptive rate: once the player's RTT has been stable + low for
            // a while, drop to every-4-ticks. Resume every-tick on any RTT
            // spike or pending-queue depth >12.
            double smoothed = d.lag.smoothed();
            double jitter = d.lag.jitter();
            boolean stable = smoothed > 0 && smoothed < 60 && jitter < 25
                    && d.pendingTransactions.size() < 12;
            if (stable && (d.serverTicks % 4) != 0) continue;

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
