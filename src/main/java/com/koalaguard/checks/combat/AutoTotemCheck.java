package com.koalaguard.checks.combat;

import com.koalaguard.KoalaGuard;
import com.koalaguard.check.CheckCategory;
import com.koalaguard.check.ListenerCheck;
import com.koalaguard.data.PlayerData;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.entity.EntityResurrectEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayDeque;
import java.util.Deque;

public final class AutoTotemCheck extends ListenerCheck {

    private static final long MAX_WAIT_MS = 2000L;

    public AutoTotemCheck(KoalaGuard plugin) {
        super(plugin, "autototem", CheckCategory.COMBAT, "Automated totem re-equipping");
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPop(EntityResurrectEvent event) {
        if (!isEnabled()) return;
        if (!(event.getEntity() instanceof Player player)) return;

        PlayerData d = plugin.getDataManager().get(player);
        if (d == null || isExempt(player)) return;

        if (d.awaitingTotem) return;

        ItemStack off = player.getInventory().getItemInOffHand();

        // distinguish stacked totems from instant refill
        boolean stackedTotem =
                off.getType() == Material.TOTEM_OF_UNDYING &&
                off.getAmount() > 1;

        if (stackedTotem) {
            if (debug()) {
                plugin.getLogger().info("[AutoTotem] " + player.getName()
                        + " skipped stacked totem");
            }
            return;
        }

        final long popNano = System.nanoTime();

        d.awaitingTotem = true;
        d.totemPopMs = System.currentTimeMillis();

        final int[] ticks = {0};
        final boolean[] sawEmpty = {false};

        final BukkitTask[] task = new BukkitTask[1];

        task[0] = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {

            if (!player.isOnline() || !d.isAlive()) {
                cleanup(d, task[0]);
                return;
            }

            ticks[0]++;

            boolean hasTotem = hasTotem(player);

            if (!hasTotem) {
                sawEmpty[0] = true;
            }

            // wait until we ACTUALLY observe empty state first
            if (sawEmpty[0] && hasTotem) {

                cleanup(d, task[0]);

                long elapsedMs =
                        (System.nanoTime() - popNano) / 1_000_000L;

                processCycle(player, d, elapsedMs, ticks[0]);

                return;
            }

            if ((System.nanoTime() - popNano) / 1_000_000L > MAX_WAIT_MS) {
                cleanup(d, task[0]);
            }

        }, 1L, 1L);
    }

    private void processCycle(Player player,
                              PlayerData d,
                              long elapsedMs,
                              int ticks) {

        if (plugin.getSafetyManager().shouldSuppressBasic(d, player)) {
            return;
        }

        int ping = getReliablePing(player, d);

        // conservative compensation
        long compensated =
                Math.max(0L, elapsedMs - Math.min(150L, ping / 3L));

        boolean packetCorroborated =
                wasInventoryActionNearPop(d);

        boolean impossibleSpeed =
                compensated <= 40L;

        boolean suspiciousSpeed =
                compensated <= 80L;

        if (debug()) {
            plugin.getLogger().info(
                    "[AutoTotem] " + player.getName()
                    + " raw=" + elapsedMs
                    + "ms comp=" + compensated
                    + "ms ticks=" + ticks
                    + " ping=" + ping
                    + " corroborated=" + packetCorroborated
            );
        }

        recordSample(d, compensated);

        // severe
        if (impossibleSpeed && packetCorroborated) {

            int vl = d.incInt(k("impossible"));

            if (vl >= 1) {
                fail(d, player,
                        "impossible autototem response "
                        + compensated + "ms");
            }

            return;
        }

        // suspicious repeated
        if (suspiciousSpeed) {

            int vl = d.incInt(k("fast"));

            if (packetCorroborated) {
                vl += 1;
            }

            if (vl >= 3) {
                fail(d, player,
                        "fast re-equip "
                        + compensated + "ms");
                d.setInt(k("fast"), 0);
            }

        } else {
            d.setInt(k("fast"), 0);
        }

        // consistency analysis
        analyzeConsistency(player, d);
    }

    private void analyzeConsistency(Player player, PlayerData d) {


        if (d.totemReequipSamples.size() < 6) {
            return;
        }

        double mean = mean(d.totemReequipSamples);
        double deviation = deviation(d.totemReequipSamples, mean);

        // modern cheats randomize slightly
        // so look for unnaturally stable low reactions
        if (mean < 350.0 && deviation < 35.0) {

            int vl = d.incInt(k("consistency"));

            if (vl >= 2) {
                fail(d, player,
                        String.format(
                                "consistent autototem mean=%.1f sd=%.1f",
                                mean,
                                deviation
                        ));

                d.setInt(k("consistency"), 0);
                d.totemReequipSamples.clear();
            }
        }
    }

    private void recordSample(PlayerData d, long value) {


        d.totemReequipSamples.addLast(value);

        while (d.totemReequipSamples.size() > 12) {
            d.totemReequipSamples.removeFirst();
        }
    }

    private boolean wasInventoryActionNearPop(PlayerData d) {

        long now = System.currentTimeMillis();

        return
                now - d.lastClickWindowMs < 150L ||
                now - d.lastOffhandSwapMs < 150L;
    }

    private int getReliablePing(Player player, PlayerData d) {

        int trans = d.transactionPing;
        int fallback = plugin.getMetrics().pingMs(player);

        if (trans <= 0) {
            return fallback;
        }

        if (fallback <= 0) {
            return trans;
        }

        return (trans + fallback) / 2;
    }

    private boolean hasTotem(Player player) {

        return
                player.getInventory().getItemInOffHand().getType()
                        == Material.TOTEM_OF_UNDYING
                ||
                player.getInventory().getItemInMainHand().getType()
                        == Material.TOTEM_OF_UNDYING;
    }

    private void cleanup(PlayerData d, BukkitTask task) {

        d.awaitingTotem = false;

        if (task != null) {
            task.cancel();
        }
    }

    private double mean(Deque<Long> values) {

        double sum = 0.0;

        for (long v : values) {
            sum += v;
        }

        return sum / values.size();
    }

    private double deviation(Deque<Long> values, double mean) {

        double sum = 0.0;

        for (long v : values) {
            double diff = v - mean;
            sum += diff * diff;
        }

        return Math.sqrt(sum / values.size());
    }
}
