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

/**
 * InvMove detection (Meteor "InventoryMove").
 *
 * The vanilla client cannot sprint while a container/inventory screen is
 * open. Clicking inside a non-player inventory while sprinting at speed is
 * the InvMove signature. A streak avoids click-then-release race FPs.
 */
public final class InventoryMoveCheck extends ListenerCheck {

    public InventoryMoveCheck(KoalaGuard plugin) {
        super(plugin, "inventorymove", CheckCategory.PLAYER, "Sprinting while a GUI is open");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onClick(InventoryClickEvent event) {
        if (!isEnabled()) return;
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (isExempt(player)) return;
        InventoryType type = event.getInventory().getType();
        if (type == InventoryType.CREATIVE || type == InventoryType.CRAFTING
                || type == InventoryType.PLAYER) return;

        PlayerData data = plugin.getDataManager().get(player);
        if (data == null) return;
        long now = System.currentTimeMillis();
        if (now - data.lastDamageMs < 800 || now - data.lastVelocityMs < 900) return;

        if (player.isSprinting() && data.deltaXZ > 0.20 && data.positionChanged) {
            int s = data.incInt(k("s"));
            if (s >= 4) {
                fail(data, player, String.format("sprint+GUI h=%.3f streak=%d", data.deltaXZ, s));
                data.setInt(k("s"), 0);
            }
        } else {
            data.addInt(k("s"), -1);
        }
    }
}
