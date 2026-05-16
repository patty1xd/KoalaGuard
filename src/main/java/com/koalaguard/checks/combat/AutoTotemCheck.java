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

/**
 * AutoTotem detection.
 *
 * A human needs ~200-500 ms to notice a totem popped and re-equip one.
 * AutoTotem re-equips on the next tick (≤ ~100 ms). We sample the offhand a
 * couple of ticks after the resurrect and flag a sustained sub-human swap.
 */
public final class AutoTotemCheck extends ListenerCheck {

    public AutoTotemCheck(KoalaGuard plugin) {
        super(plugin, "autototem", CheckCategory.COMBAT, "Re-equipping a totem faster than humanly possible");
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onResurrect(EntityResurrectEvent event) {
        if (!isEnabled()) return;
        if (!(event.getEntity() instanceof Player player)) return;
        if (isExempt(player)) return;
        PlayerData data = plugin.getDataManager().get(player);
        if (data == null) return;

        final long start = System.currentTimeMillis();
        long minMs = cfgI("min-swap-ticks", 3) * 50L;

        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (!player.isOnline() || !data.isAlive()) return;
            if (player.getInventory().getItemInOffHand().getType() != Material.TOTEM_OF_UNDYING) return;
            long elapsed = System.currentTimeMillis() - start;
            if (elapsed <= minMs + 30) {
                int s = data.incInt(k("s"));
                if (s >= 2) {
                    fail(data, player, "totem re-equip " + elapsed + "ms streak=" + s);
                    data.setInt(k("s"), 0);
                }
            } else {
                data.setInt(k("s"), 0);
            }
        }, 2L);
    }
}
