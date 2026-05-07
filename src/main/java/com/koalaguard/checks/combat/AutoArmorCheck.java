package com.koalaguard.checks.combat;

import com.koalaguard.KoalaGuard;
import com.koalaguard.checks.Check;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.ItemStack;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class AutoArmorCheck extends Check {

    private final Map<UUID, Long> lastArmorSwap = new HashMap<>();
    private final Map<UUID, Integer> swapCount = new HashMap<>();

    public AutoArmorCheck(KoalaGuard plugin) { super(plugin, "autoarmor"); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!isEnabled()) return;
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (player.hasPermission("koalaguard.bypass")) return;

        // Detect clicking armor slots
        if (event.getSlotType() != InventoryType.SlotType.ARMOR) return;

        UUID uuid = player.getUniqueId();
        long now = System.currentTimeMillis();
        long last = lastArmorSwap.getOrDefault(uuid, 0L);

        if (now - last < 50) { // within 1 tick
            int count = swapCount.merge(uuid, 1, Integer::sum);
            if (count >= 4) { // all 4 armor pieces swapped in <200ms
                flag(player, "full_armor_swap_" + (now - last) + "ms");
                swapCount.put(uuid, 0);
            }
        } else {
            swapCount.put(uuid, 1);
        }
        lastArmorSwap.put(uuid, now);
    }
}
