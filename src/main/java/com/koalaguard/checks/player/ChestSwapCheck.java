package com.koalaguard.checks.player;

import com.koalaguard.KoalaGuard;
import com.koalaguard.check.CheckCategory;
import com.koalaguard.check.ListenerCheck;
import com.koalaguard.data.PlayerData;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.ItemStack;

/**
 * ChestSwap detection — instant chestplate/elytra hot-swapping in the armour
 * slot, far faster than opening the inventory and clicking manually.
 */
public final class ChestSwapCheck extends ListenerCheck {

    private static final int CHEST_SLOT = 38;

    public ChestSwapCheck(KoalaGuard plugin) {
        super(plugin, "chestswap", CheckCategory.PLAYER, "Instant chestplate/elytra swapping");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onClick(InventoryClickEvent event) {
        if (!isEnabled()) return;
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (isExempt(player)) return;
        if (event.getSlotType() != InventoryType.SlotType.ARMOR || event.getSlot() != CHEST_SLOT) return;
        ItemStack cursor = event.getCursor();
        if (cursor == null || cursor.getType().isAir()) return;
        String n = cursor.getType().name();
        if (!n.contains("CHESTPLATE") && !n.equals("ELYTRA")) return;

        PlayerData data = plugin.getDataManager().get(player);
        if (data == null) return;

        long now = System.currentTimeMillis();
        long last = data.getLong(k("last"));
        data.setLong(k("last"), now);
        long minMs = cfgI("min-swap-ticks", 3) * 50L;
        if (last != 0 && now - last < minMs) {
            int s = data.incInt(k("s"));
            if (s >= 2) {
                fail(data, player, "chestswap gap=" + (now - last) + "ms streak=" + s);
                data.setInt(k("s"), 0);
            }
        } else {
            data.setInt(k("s"), 0);
        }
    }
}
