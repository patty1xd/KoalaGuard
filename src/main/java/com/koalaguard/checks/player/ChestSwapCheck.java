package com.koalaguard.checks.player;

import com.koalaguard.KoalaGuard;
import com.koalaguard.checks.Check;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.ItemStack;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class ChestSwapCheck extends Check {

    private final Map<UUID, Long> lastChestSwap = new HashMap<>();
    private final Map<UUID, Integer> swapCount = new HashMap<>();

    public ChestSwapCheck(KoalaGuard plugin) { super(plugin, "chestswap"); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!isEnabled()) return;
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (isExempt(player)) return;
        if (plugin.shouldSuppressFlags(player)) return;
        if (event.getSlotType() != InventoryType.SlotType.ARMOR) return;

        // Chest slot = slot 38 in player inventory
        if (event.getSlot() != 38) return;

        UUID uuid = player.getUniqueId();
        long now = System.currentTimeMillis();
        long last = lastChestSwap.getOrDefault(uuid, 0L);

        int minTicks = plugin.getConfig().getInt("checks.chestswap.min-swap-ticks", 3);
        long minMs = minTicks * 50L;

        if (now - last < minMs && last != 0) {
            flag(player, "swap=" + (now - last) + "ms");
        }
        lastChestSwap.put(uuid, now);
    }
}
