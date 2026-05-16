package com.koalaguard.checks.combat;

import com.koalaguard.KoalaGuard;
import com.koalaguard.check.CheckCategory;
import com.koalaguard.check.ListenerCheck;
import com.koalaguard.data.PlayerData;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryType;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * AutoArmor detection — 3+ armour-slot clicks within ~80 ms (a human cannot
 * click distinct armour slots faster than ~150 ms apart). Repeated bursts
 * required.
 */
public final class AutoArmorCheck extends ListenerCheck {

    public AutoArmorCheck(KoalaGuard plugin) {
        super(plugin, "autoarmor", CheckCategory.COMBAT, "Auto-equipping armour inhumanly fast");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onClick(InventoryClickEvent event) {
        if (!isEnabled()) return;
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (isExempt(player)) return;
        if (event.getSlotType() != InventoryType.SlotType.ARMOR) return;
        PlayerData data = plugin.getDataManager().get(player);
        if (data == null) return;

        long now = System.currentTimeMillis();
        Deque<Long> w = data.obj(k("w"));
        if (w == null) { w = new ArrayDeque<>(); data.setObj(k("w"), w); }
        w.addLast(now);
        while (!w.isEmpty() && now - w.peekFirst() > 90) w.removeFirst();

        if (w.size() >= 3) {
            int s = data.incInt(k("s"));
            if (s >= 2) {
                fail(data, player, "armor burst=" + w.size() + " streak=" + s);
                data.setInt(k("s"), 0);
            }
            w.clear();
        }
    }
}
