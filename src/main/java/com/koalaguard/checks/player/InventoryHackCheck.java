package com.koalaguard.checks.player;

import com.koalaguard.KoalaGuard;
import com.koalaguard.check.CheckCategory;
import com.koalaguard.check.ListenerCheck;
import com.koalaguard.data.PlayerData;
import com.koalaguard.util.MathUtil;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.inventory.InventoryClickEvent;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Inventory automation detection — clicking container/inventory slots at a
 * machine rate with near-zero interval variance (auto-sort/steal/clean).
 * Tuned conservatively so fast manual sorting never trips it.
 */
public final class InventoryHackCheck extends ListenerCheck {

    public InventoryHackCheck(KoalaGuard plugin) {
        super(plugin, "inventoryhack", CheckCategory.PLAYER, "Automated inventory clicking");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onClick(InventoryClickEvent event) {
        if (!isEnabled()) return;
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (isExempt(player)) return;
        PlayerData data = plugin.getDataManager().get(player);
        if (data == null) return;

        long now = System.currentTimeMillis();
        Deque<Long> t = data.obj(k("t"));
        if (t == null) { t = new ArrayDeque<>(); data.setObj(k("t"), t); }
        t.addLast(now);
        while (!t.isEmpty() && now - t.peekFirst() > 1000) t.removeFirst();

        if (t.size() > cfgI("max-clicks-per-sec", 18)) {
            fail(data, player, "click rate=" + t.size() + "/s");
            t.clear();
            return;
        }
        if (t.size() >= 14) {
            List<Long> iv = new ArrayList<>();
            Long p = null;
            for (Long v : t) { if (p != null) iv.add(v - p); p = v; }
            if (MathUtil.variance(iv) < 18 && MathUtil.duplicates(iv) >= 5) {
                fail(data, player, "machine clicks var=" + (int) MathUtil.variance(iv));
                t.clear();
            }
        }
    }
}
