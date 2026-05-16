package com.koalaguard.checks.player;

import com.koalaguard.KoalaGuard;
import com.koalaguard.check.CheckCategory;
import com.koalaguard.check.ListenerCheck;
import com.koalaguard.data.PlayerData;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.inventory.ItemStack;

/**
 * AutoReplenish detection — the active hotbar slot's stack count jumps
 * upward within ~150 ms of leaving and returning to it, with no inventory
 * interaction. Streak required.
 */
public final class AutoReplenishCheck extends ListenerCheck {

    public AutoReplenishCheck(KoalaGuard plugin) {
        super(plugin, "autoreplenish", CheckCategory.PLAYER, "Auto-refilling hotbar stacks");
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onHeld(PlayerItemHeldEvent event) {
        if (!isEnabled()) return;
        Player player = event.getPlayer();
        if (isExempt(player)) return;
        PlayerData data = plugin.getDataManager().get(player);
        if (data == null) return;

        long now = System.currentTimeMillis();
        int newSlot = event.getNewSlot();
        int lastSlot = (int) data.getLong(k("slot")) - 1;
        int lastCount = data.getInt(k("cnt"));
        long lastMs = data.getLong(k("ms"));

        if (lastSlot == newSlot && lastMs != 0 && now - lastMs < 150) {
            ItemStack back = player.getInventory().getItem(newSlot);
            int c = back != null ? back.getAmount() : 0;
            if (c > lastCount + 8) {
                int s = data.incInt(k("s"));
                if (s >= 3) {
                    fail(data, player, "replenish slot=" + newSlot + " " + lastCount + "→" + c
                            + " in " + (now - lastMs) + "ms streak=" + s);
                    data.setInt(k("s"), 0);
                }
            }
        }

        ItemStack leaving = player.getInventory().getItem(event.getPreviousSlot());
        data.setLong(k("slot"), event.getPreviousSlot() + 1L);
        data.setInt(k("cnt"), leaving != null ? leaving.getAmount() : 0);
        data.setLong(k("ms"), now);
    }
}
