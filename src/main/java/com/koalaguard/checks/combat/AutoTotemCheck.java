package com.koalaguard.checks.combat;

import com.koalaguard.KoalaGuard;
import com.koalaguard.checks.Check;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.entity.EntityResurrectEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class AutoTotemCheck extends Check {

    private final Map<UUID, Long> lastTotemUse = new HashMap<>();
    private final Map<UUID, Long> lastOffhandSwap = new HashMap<>();

    public AutoTotemCheck(KoalaGuard plugin) { super(plugin, "autototem"); }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onResurrect(EntityResurrectEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        lastTotemUse.put(player.getUniqueId(), System.currentTimeMillis());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!isEnabled()) return;
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (player.hasPermission("koalaguard.bypass")) return;

        ItemStack cursor = event.getCursor();
        ItemStack current = event.getCurrentItem();

        boolean movingTotem = (cursor != null && cursor.getType() == Material.TOTEM_OF_UNDYING)
                || (current != null && current.getType() == Material.TOTEM_OF_UNDYING);
        if (!movingTotem) return;

        UUID uuid = player.getUniqueId();
        long now = System.currentTimeMillis();
        long lastUse = lastTotemUse.getOrDefault(uuid, 0L);

        // If totem was used very recently and they're swapping a new one in
        int minTicks = plugin.getConfig().getInt("checks.autototem.min-swap-ticks", 2);
        long minMs = minTicks * 50L;

        if (now - lastUse < minMs) {
            flag(player, "swap_after=" + (now - lastUse) + "ms");
        }
    }
}
