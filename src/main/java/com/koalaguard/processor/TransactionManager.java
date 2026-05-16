package com.koalaguard.processor;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPing;
import com.koalaguard.KoalaGuard;
import com.koalaguard.data.PlayerData;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Real lag compensation via the transaction (Ping/Pong) packet.
 *
 * Each server tick we stamp the player with a Ping carrying a unique id and
 * record the send time. The client must echo it back (Pong) — the round trip
 * is the player's TRUE latency (independent of the spoofable status ping),
 * and the server-tick counter it advances is the authoritative clock the
 * Timer check uses (so server lag can never be mistaken for a timer).
 */
public final class TransactionManager extends BukkitRunnable {

    private final KoalaGuard plugin;
    private final AtomicInteger nextId = new AtomicInteger(1);

    public TransactionManager(KoalaGuard plugin) {
        this.plugin = plugin;
    }

    @Override
    public void run() {
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            PlayerData d = plugin.getDataManager().get(player);
            if (d == null) continue;

            d.serverTicks++;

            long now = System.currentTimeMillis();
            d.pendingTransactions.values().removeIf(sentNanos ->
                    (System.nanoTime() - sentNanos) / 1_000_000L > 5000);

            if (d.pendingTransactions.size() > 40) continue; // client not answering

            int id = nextId.getAndIncrement();
            if (id > 1_000_000_00) nextId.set(1);
            d.pendingTransactions.put(id, System.nanoTime());
            d.lastTransactionSentMs = now;
            try {
                PacketEvents.getAPI().getPlayerManager()
                        .sendPacket(player, new WrapperPlayServerPing(id));
            } catch (Throwable ignored) {
                d.pendingTransactions.remove(id);
            }
        }
    }

    /** Called from the packet thread when the client echoes a Pong. */
    public void onPong(UUID uuid, int id) {
        PlayerData d = plugin.getDataManager().get(uuid);
        if (d == null) return;
        Long sent = d.pendingTransactions.remove(id);
        if (sent == null) return;
        int rtt = (int) ((System.nanoTime() - sent) / 1_000_000L);
        d.transactionPing = rtt;
        d.lastPongMs = System.currentTimeMillis();
        d.confirmedTransactions++;
    }
}
