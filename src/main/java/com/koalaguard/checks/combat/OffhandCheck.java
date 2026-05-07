package com.koalaguard.checks.combat;

import com.koalaguard.KoalaGuard;
import com.koalaguard.checks.Check;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryType;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class OffhandCheck extends Check {

    private final Map<UUID, Long> lastOffhandSwap = new HashMap<>();
    private final Map<UUID, Integer> swapCount = new HashMap<>();

    public OffhandCheck(KoalaGuard plugin) { super(plugin, "offhand"); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!isEnabled()) return;
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (isExempt(player)) return;
        if (plugin.shouldSuppressFlags(player)) return;
        if (event.getSlotType() != InventoryType.SlotType.QUICKBAR) return;

        UUID uuid = player.getUniqueId();
        long now = System.currentTimeMillis();
        long last = lastOffhandSwap.getOrDefault(uuid, 0L);

        // Conservative: very fast repeated swaps only
        if (now - last < 60) {
            int count = swapCount.merge(uuid, 1, Integer::sum);
            if (count >= 8) {
                flag(player, "rapid_swap count=" + count);
                swapCount.put(uuid, 0);
            }
        } else {
            swapCount.put(uuid, 1);
        }
        lastOffhandSwap.put(uuid, now);
    }
}
