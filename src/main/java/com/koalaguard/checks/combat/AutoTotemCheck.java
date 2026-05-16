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
 * AutoTotem — TotemGuard "totem cycle" model, fixed.
 *
 * The old build could NEVER fire: it ran the global safety gate, which
 * suppresses everything for the damage-grace window — but a totem only pops
 * *because* the player just took lethal damage. It now uses the basic gate
 * (server health + teleport only) and transaction-measured latency.
 *
 * Cycle = pop ({@link EntityResurrectEvent}) → time until a totem is back in
 * a hand (polled every tick until it returns). Signals:
 *   A) single ping-compensated re-equip faster than humanly possible,
 *   B) unnaturally low std-dev of re-equip times across cycles,
 *   C) client brand / plugin message advertises an autototem mod.
 */
public final class AutoTotemCheck extends ListenerCheck {

    public AutoTotemCheck(KoalaGuard plugin) {
        super(plugin, "autototem", CheckCategory.COMBAT, "Automated totem re-equipping");
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPop(EntityResurrectEvent event) {
        if (!isEnabled()) return;
        if (!(event.getEntity() instanceof Player player)) return;
        if (isExempt(player)) return;
        PlayerData d = plugin.getDataManager().get(player);
        if (d == null) return;

        if (d.flagBadBrand) {
            fail(d, player, "client brand advertises autototem: " + d.packetBrand);
        }
        if (d.awaitingTotem) return; // a cycle is already being measured

        final long pop = System.currentTimeMillis();
        d.totemPopMs = pop;
        d.awaitingTotem = true;

        final int[] ticks = {0};
        final BukkitTask[] holder = new BukkitTask[1];
        holder[0] = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            if (!player.isOnline() || !d.isAlive()) { d.awaitingTotem = false; holder[0].cancel(); return; }
            ticks[0]++;
            boolean hasTotem =
                    player.getInventory().getItemInOffHand().getType() == Material.TOTEM_OF_UNDYING
                 || player.getInventory().getItemInMainHand().getType() == Material.TOTEM_OF_UNDYING;
            if (hasTotem) {
                d.awaitingTotem = false;
                holder[0].cancel();
                process(player, d, System.currentTimeMillis() - pop);
            } else if (ticks[0] >= 30) { // 1.5 s — gave up (manual / no totem left)
                d.awaitingTotem = false;
                holder[0].cancel();
            }
        }, 1L, 1L);
    }

    private void process(Player player, PlayerData d, long delay) {
        if (plugin.getSafetyManager().shouldSuppressBasic(d, player)) return;

        int ping = d.transactionPing > 0 ? d.transactionPing : plugin.getMetrics().pingMs(player);
        long comp = Math.max(0, delay - (ping > 0 ? ping / 2L : 0));

        d.totemReequipSamples.addLast(comp);
        while (d.totemReequipSamples.size() > 20) d.totemReequipSamples.removeFirst();

        if (debug()) plugin.getLogger().info("[AutoTotem] " + player.getName()
                + " cycle delay=" + delay + "ms comp=" + comp + "ms ping=" + ping);

        // A — impossibly fast single cycle
        if (comp < cfgL("min-reequip-ms", 150L)) {
            int s = d.incInt(k("fast"));
            if (s >= 2) {
                fail(d, player, "re-equip " + comp + "ms (ping-comp, " + delay + "ms raw) streak=" + s);
                d.setInt(k("fast"), 0);
            }
        } else {
            d.setInt(k("fast"), 0);
        }

        // B — machine-consistent timing across cycles
        if (d.totemReequipSamples.size() >= cfgI("sample-size", 6)) {
            List<Long> s = new ArrayList<>(d.totemReequipSamples);
            double mean = MathUtil.average(s);
            double sd = MathUtil.standardDeviation(s);
            if (sd < cfgD("max-stddev", 35.0) && mean < cfgD("max-mean-ms", 950.0)) {
                fail(d, player, String.format("consistent re-equip mean=%.0fms sd=%.1f n=%d",
                        mean, sd, s.size()));
                d.totemReequipSamples.clear();
            }
        }
    }
}
