package com.koalaguard.checks.world;

import com.koalaguard.KoalaGuard;
import com.koalaguard.check.CheckCategory;
import com.koalaguard.check.ListenerCheck;
import com.koalaguard.data.PlayerData;
import com.koalaguard.util.MathUtil;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.inventory.FurnaceExtractEvent;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * AutoSmelter detection — furnace extractions at a machine rate / cadence
 * that no human clicking can produce.
 */
public final class AutoSmelterCheck extends ListenerCheck {

    public AutoSmelterCheck(KoalaGuard plugin) {
        super(plugin, "autosmelter", CheckCategory.WORLD, "Automated furnace extraction");
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onExtract(FurnaceExtractEvent event) {
        if (!isEnabled()) return;
        Player player = event.getPlayer();
        if (isExempt(player)) return;
        PlayerData data = plugin.getDataManager().get(player);
        if (data == null) return;

        long now = System.currentTimeMillis();
        Deque<Long> t = data.obj(k("t"));
        if (t == null) { t = new ArrayDeque<>(); data.setObj(k("t"), t); }
        t.addLast(now);
        while (!t.isEmpty() && now - t.peekFirst() > 1000) t.removeFirst();

        if (t.size() > cfgI("max-extracts-per-sec", 5)) {
            fail(data, player, "extract rate=" + t.size() + "/s");
            t.clear();
            return;
        }
        if (t.size() >= 6) {
            List<Long> iv = new ArrayList<>();
            Long p = null;
            for (Long v : t) { if (p != null) iv.add(v - p); p = v; }
            if (MathUtil.variance(iv) < 20 && MathUtil.average(iv) < 45) {
                fail(data, player, "machine extract var=" + (int) MathUtil.variance(iv));
                t.clear();
            }
        }
    }
}
