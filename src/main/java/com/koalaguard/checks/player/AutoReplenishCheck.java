package com.koalaguard.checks.player;

import com.koalaguard.KoalaGuard;
import com.koalaguard.checks.Check;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.inventory.ItemStack;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class AutoReplenishCheck extends Check {

    private final Map<UUID, Integer> prevSlotItemCount = new HashMap<>();
    private final Map<UUID, Long> lastReplenish = new HashMap<>();

    public AutoReplenishCheck(KoalaGuard plugin) { super(plugin, "autoreplenish"); }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onHeldChange(PlayerItemHeldEvent event) {
        if (!isEnabled()) return;
        Player player = event.getPlayer();
        if (player.hasPermission("koalaguard.bypass")) return;

        UUID uuid = player.getUniqueId();
        int prevSlot = event.getPreviousSlot();
        ItemStack prevItem = player.getInventory().getItem(prevSlot);

        int prevCount = prevSlotItemCount.getOrDefault(uuid, -1);
        int currCount = prevItem != null ? prevItem.getAmount() : 0;

        // If item count jumped up compared to previous visit to this slot = replenish
        if (prevCount >= 0 && currCount > prevCount + 1) {
            long now = System.currentTimeMillis();
            long last = lastReplenish.getOrDefault(uuid, 0L);
            if (now - last < 200) { // replenished very recently
                flag(player, "replenish_jump=" + prevCount + "->" + currCount);
            }
            lastReplenish.put(uuid, now);
        }

        prevSlotItemCount.put(uuid, currCount);
    }
}
