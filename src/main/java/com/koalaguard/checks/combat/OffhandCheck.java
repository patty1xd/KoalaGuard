package com.koalaguard.checks.combat;

import com.koalaguard.KoalaGuard;
import com.koalaguard.check.CheckCategory;
import com.koalaguard.check.ListenerCheck;
import com.koalaguard.data.PlayerData;
import com.koalaguard.util.MathUtil;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Offhand-manager detection — automated F-key offhand juggling. Flags an
 * inhuman swap rate or machine-regular swap intervals.
 */
public final class OffhandCheck extends ListenerCheck {

    public OffhandCheck(KoalaGuard plugin) {
        super(plugin, "offhand", CheckCategory.COMBAT, "Automated offhand swapping");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSwap(PlayerSwapHandItemsEvent event) {
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

        if (t.size() > cfgI("max-swaps-per-sec", 10)) {
            fail(data, player, "swap rate=" + t.size() + "/s");
            t.clear();
            return;
        }
        if (t.size() >= 10) {
            List<Long> iv = new ArrayList<>();
            Long prev = null;
            for (Long v : t) { if (prev != null) iv.add(v - prev); prev = v; }
            if (MathUtil.variance(iv) < 25 && MathUtil.duplicates(iv) >= 4) {
                fail(data, player, "machine swap var=" + (int) MathUtil.variance(iv));
                t.clear();
            }
        }
    }
}
