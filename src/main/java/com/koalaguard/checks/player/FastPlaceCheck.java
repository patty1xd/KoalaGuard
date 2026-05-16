package com.koalaguard.checks.player;

import com.koalaguard.KoalaGuard;
import com.koalaguard.check.CheckCategory;
import com.koalaguard.check.ListenerCheck;
import com.koalaguard.data.PlayerData;
import com.koalaguard.util.MathUtil;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.BlockPlaceEvent;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * FastPlace detection (Meteor "FastUse"/place spam).
 *
 * Vanilla gates block placement to a few per second. FastPlace removes the
 * cooldown, producing a placement rate / cadence no human right-click hand
 * can sustain. Tuned above practiced building speed to stay FP-free.
 */
public final class FastPlaceCheck extends ListenerCheck {

    public FastPlaceCheck(KoalaGuard plugin) {
        super(plugin, "fastplace", CheckCategory.PLAYER, "Placing blocks faster than possible");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
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

        if (t.size() > cfgI("max-per-sec", 12)) {
            fail(data, player, "place rate=" + t.size() + "/s");
            t.clear();
            return;
        }
        if (t.size() >= 10) {
            List<Long> iv = new ArrayList<>();
            Long p = null;
            for (Long v : t) { if (p != null) iv.add(v - p); p = v; }
            if (MathUtil.variance(iv) < 16 && MathUtil.average(iv) < 75) {
                fail(data, player, "machine place var=" + (int) MathUtil.variance(iv));
                t.clear();
            }
        }
    }
}
