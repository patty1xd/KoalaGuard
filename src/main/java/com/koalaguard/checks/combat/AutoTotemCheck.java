package com.koalaguard.checks.combat;

import com.koalaguard.KoalaGuard;
import com.koalaguard.check.CheckCategory;
import com.koalaguard.check.ListenerCheck;
import com.koalaguard.data.PlayerData;
import com.koalaguard.util.MathUtil;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.entity.EntityResurrectEvent;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.List;

/**
 * AutoTotem — robust totem-cycle model.
 *
 * Cycle: a totem pops ({@link EntityResurrectEvent}) → the off/main hand is
 * polled every tick until a totem returns. The re-equip time is ping
 * compensated (transaction RTT) and compared to what a human can do
 * (notice ≈250 ms + open/press + click ≈ 400-900 ms). Signals:
 *   A) a single ping-comp re-equip far faster than humanly possible,
 *   B) low std-dev of re-equip times across cycles (bot consistency),
 *   C) a totem-to-offhand window-click / swap fired right after the pop
 *      while no inventory was meaningfully open (packet corroboration),
 *   D) client brand / plugin message advertises an autototem mod.
 *
 * The legit "carrying a totem STACK" case (hand still holds a totem the very
 * next tick) is skipped — it is unmeasurable and would false-positive.
 *
 * NOTE: if you test as OP, ensure you do NOT have `koalaguard.bypass`.
 */
public final class AutoTotemCheck extends ListenerCheck {

    public AutoTotemCheck(KoalaGuard plugin) {
        super(plugin, "autototem", CheckCategory.COMBAT, "Automated totem re-equipping");
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPop(EntityResurrectEvent event) {
        if (!isEnabled()) return;
        if (!(event.getEntity() instanceof Player player)) return;
        PlayerData d = plugin.getDataManager().get(player);
        if (d == null) return;

        boolean exempt = isExempt(player);
        if (debug()) plugin.getLogger().info("[AutoTotem] " + player.getName()
                + " totem popped (exempt=" + exempt + ", awaiting=" + d.awaitingTotem + ")");
        if (exempt) return;

        if (d.flagBadBrand) {
            fail(d, player, "client brand advertises autototem: " + d.packetBrand);
        }
        if (d.awaitingTotem) return;

        final long pop = System.currentTimeMillis();
        d.totemPopMs = pop;
        d.awaitingTotem = true;

        final int[] tick = {0};
        final boolean[] sawEmpty = {false};
        final BukkitTask[] task = new BukkitTask[1];
        task[0] = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            if (!player.isOnline() || !d.isAlive()) { d.awaitingTotem = false; task[0].cancel(); return; }
            tick[0]++;
            boolean hasTotem =
                    player.getInventory().getItemInOffHand().getType() == Material.TOTEM_OF_UNDYING
                 || player.getInventory().getItemInMainHand().getType() == Material.TOTEM_OF_UNDYING;

            if (tick[0] == 1 && hasTotem) {
                // hand still holds a totem next tick → a STACK was carried,
                // not a re-equip. Unmeasurable; abort to avoid a false flag.
                if (debug()) plugin.getLogger().info("[AutoTotem] " + player.getName()
                        + " skipped (totem stack — not a measurable cycle)");
                d.awaitingTotem = false; task[0].cancel();
                return;
            }
            if (!hasTotem) { sawEmpty[0] = true; }

            if (sawEmpty[0] && hasTotem) {
                d.awaitingTotem = false; task[0].cancel();
                process(player, d, System.currentTimeMillis() - pop, pop);
            } else if (tick[0] >= 40) {            // 2 s — gave up (manual / out of totems)
                if (debug()) plugin.getLogger().info("[AutoTotem] " + player.getName()
                        + " no re-equip within 2s (manual / none)");
                d.awaitingTotem = false; task[0].cancel();
            }
        }, 1L, 1L);
    }

    private void process(Player player, PlayerData d, long delay, long pop) {
        if (plugin.getSafetyManager().shouldSuppressBasic(d, player)) {
            if (debug()) plugin.getLogger().info("[AutoTotem] " + player.getName()
                    + " cycle suppressed (server unstable)");
            return;
        }

        int ping = d.transactionPing > 0 ? d.transactionPing : plugin.getMetrics().pingMs(player);
        long comp = Math.max(0, delay - (ping > 0 ? ping / 2L : 0));

        boolean packetCorroborated =
                (d.lastClickWindowMs >= pop && d.lastClickWindowMs - pop < 250)
             || (d.lastOffhandSwapMs >= pop && d.lastOffhandSwapMs - pop < 250);

        d.totemReequipSamples.addLast(comp);
        while (d.totemReequipSamples.size() > 20) d.totemReequipSamples.removeFirst();

        if (debug()) plugin.getLogger().info(String.format(
                "[AutoTotem] %s cycle: raw=%dms comp=%dms ping=%d corroborated=%b samples=%d",
                player.getName(), delay, comp, ping, packetCorroborated, d.totemReequipSamples.size()));

        long minMs = cfgL("min-reequip-ms", 250L);

        // A — impossibly fast re-equip
        if (comp <= minMs) {
            int need = packetCorroborated ? 1 : 2;
            int s = d.incInt(k("fast"));
            if (s >= need) {
                fail(d, player, "re-equip " + comp + "ms (ping-comp, raw " + delay + "ms)"
                        + (packetCorroborated ? " + click/swap packet" : "") + " streak=" + s);
                d.setInt(k("fast"), 0);
            }
        } else {
            d.setInt(k("fast"), 0);
        }

        // B — machine-consistent timing across cycles
        if (d.totemReequipSamples.size() >= cfgI("sample-size", 4)) {
            List<Long> s = new ArrayList<>(d.totemReequipSamples);
            double mean = MathUtil.average(s);
            double sd = MathUtil.standardDeviation(s);
            if (sd < cfgD("max-stddev", 60.0) && mean < cfgD("max-mean-ms", 600.0)) {
                fail(d, player, String.format("consistent re-equip mean=%.0fms sd=%.1f n=%d",
                        mean, sd, s.size()));
                d.totemReequipSamples.clear();
            }
        }
    }
}
