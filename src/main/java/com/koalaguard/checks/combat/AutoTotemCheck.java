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

import java.util.ArrayList;
import java.util.List;

/**
 * AutoTotem — TotemGuard-style "totem cycle" model.
 *
 * A cycle = totem pops ({@link EntityResurrectEvent}) → time until a totem
 * is back in the off-hand. Three independent signals:
 *   A) Single ping-compensated re-equip faster than a human can (≈ <150 ms).
 *   B) Unnaturally low standard deviation of re-equip times across cycles
 *      (a bot's timing clusters; a human's scatters).
 *   C) Client brand / plugin message advertises an autototem mod (BadPackets).
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

        final long pop = System.currentTimeMillis();
        d.totemPopMs = pop;
        d.awaitingTotem = true;
        sample(player, d, pop, 1);
    }

    /** Re-checks the off-hand at 1,2,3,5,8 ticks until a totem returns. */
    private void sample(Player player, PlayerData d, long pop, int tick) {
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (!player.isOnline() || !d.isAlive() || !d.awaitingTotem) return;
            boolean hasTotem = player.getInventory().getItemInOffHand().getType() == Material.TOTEM_OF_UNDYING;
            if (hasTotem) {
                d.awaitingTotem = false;
                process(player, d, System.currentTimeMillis() - pop);
            } else if (tick < 9) {
                int next = tick == 1 ? 2 : tick == 2 ? 3 : tick == 3 ? 5 : 8;
                sample(player, d, pop, next);
            } else {
                d.awaitingTotem = false; // gave up — manual / no totem
            }
        }, tick == 1 ? 1L : 1L);
    }

    private void process(Player player, PlayerData d, long delay) {
        if (plugin.getSafetyManager().shouldSuppress(d, player)) return;

        int ping = plugin.getMetrics().pingMs(player);
        long comp = Math.max(0, delay - (ping > 0 ? ping / 2L : 0));

        d.totemReequipSamples.addLast(comp);
        while (d.totemReequipSamples.size() > 20) d.totemReequipSamples.removeFirst();

        // A — impossibly fast single cycle
        if (comp < cfgL("min-reequip-ms", 150L)) {
            int s = d.incInt(k("fast"));
            if (s >= 2) {
                fail(d, player, "re-equip " + comp + "ms (ping-comp) streak=" + s);
                d.setInt(k("fast"), 0);
            }
        } else {
            d.setInt(k("fast"), 0);
        }

        // B — machine-consistent timing across cycles
        if (d.totemReequipSamples.size() >= cfgI("sample-size", 8)) {
            List<Long> s = new ArrayList<>(d.totemReequipSamples);
            double mean = MathUtil.average(s);
            double sd = MathUtil.standardDeviation(s);
            if (sd < cfgD("max-stddev", 32.0) && mean < cfgD("max-mean-ms", 900.0)) {
                fail(d, player, String.format("consistent re-equip mean=%.0fms sd=%.1f n=%d",
                        mean, sd, s.size()));
                d.totemReequipSamples.clear();
            }
        }
    }
}
