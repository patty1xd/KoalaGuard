package com.koalaguard.checks.player;

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

/**
 * Detects Meteor ChestSwap.
 *
 * From Meteor's source (ChestSwap.java):
 *   - Opens inventory, uses InvUtils.move() to place a chestplate from inventory
 *     into the armor chestplate slot (slot 38 in the player inventory), then
 *     closes inventory — all within 0–1 ticks (< 100 ms).
 *   - The observable event: InventoryClickEvent on armor slot 38 (chest slot)
 *     fires, changing the item to a chestplate.
 *   - Human minimum: ~200 ms to open inventory, find item, click, close.
 *
 * Detection:
 *   Time between consecutive chestplate armor-slot clicks < minMs.
 *   Also flag if chestplate is equipped while combat is active (within 3s of last hit)
 *   AND the swap was within 2 ticks (100 ms) — common ChestSwap usage pattern.
 */
public class ChestSwapCheck extends Check {

    private final Map<UUID, Long>    lastSwapMs    = new HashMap<>();
    private final Map<UUID, Long>    lastHitMs     = new HashMap<>();
    private final Map<UUID, Integer> swapStreak    = new HashMap<>();

    private static final int  ARMOR_CHEST_SLOT = 38;

    public ChestSwapCheck(KoalaGuard plugin) { super(plugin, "chestswap"); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!isEnabled()) return;
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (isExempt(player)) return;
        if (plugin.shouldSuppressFlags(player)) return;
        if (event.getSlotType() != InventoryType.SlotType.ARMOR) return;
        if (event.getSlot() != ARMOR_CHEST_SLOT) return;

        // Only flag when a chestplate is being placed INTO the slot
        ItemStack cursor = event.getCursor();
        if (cursor == null || cursor.getType() == Material.AIR) return;
        String name = cursor.getType().name();
        if (!name.contains("CHESTPLATE") && !name.contains("TUNIC")) return;

        UUID uuid = player.getUniqueId();
        long now  = System.currentTimeMillis();

        int minTicks = plugin.getConfig().getInt("checks.chestswap.min-swap-ticks", 3);
        long minMs   = minTicks * 50L;

        Long lastMs = lastSwapMs.get(uuid);
        if (lastMs != null && now - lastMs < minMs) {
            int s = swapStreak.merge(uuid, 1, Integer::sum);
            if (s >= 2) {
                flag(player, "chestswap elapsed=" + (now - lastMs) + "ms min=" + minMs + "ms streak=" + s);
                swapStreak.put(uuid, 0);
            }
        } else {
            // Also flag single fast swap during active combat
            Long hitMs = lastHitMs.get(uuid);
            if (hitMs != null && now - hitMs < 3000 && lastMs != null && now - lastMs < 100) {
                flag(player, "combat_chestswap elapsed=" + (now - lastMs) + "ms");
            }
            swapStreak.put(uuid, 0);
        }

        lastSwapMs.put(uuid, now);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(org.bukkit.event.entity.EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player player)) return;
        if (!(event.getEntity() instanceof Player)) return;
        lastHitMs.put(player.getUniqueId(), System.currentTimeMillis());
    }
}
