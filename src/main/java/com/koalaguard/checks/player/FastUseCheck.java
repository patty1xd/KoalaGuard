package com.koalaguard.checks.player;

import com.koalaguard.KoalaGuard;
import com.koalaguard.check.CheckCategory;
import com.koalaguard.check.ListenerCheck;
import com.koalaguard.data.PlayerData;
import com.koalaguard.util.MathUtil;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.entity.ProjectileLaunchEvent;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * FastUse detection — throwing consumable projectiles (pearls, snowballs,
 * eggs, splash potions) faster than a human can right-click, or at a
 * machine-regular cadence.
 */
public final class FastUseCheck extends ListenerCheck {

    public FastUseCheck(KoalaGuard plugin) {
        super(plugin, "fastuse", CheckCategory.PLAYER, "Using/throwing items faster than possible");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onLaunch(ProjectileLaunchEvent event) {
        if (!isEnabled()) return;
        Projectile proj = event.getEntity();
        if (!(proj.getShooter() instanceof Player player)) return;
        if (!(proj instanceof EnderPearl || proj instanceof Snowball || proj instanceof Egg
                || proj instanceof ThrownPotion)) return;
        if (isExempt(player)) return;
        PlayerData data = plugin.getDataManager().get(player);
        if (data == null) return;

        long now = System.currentTimeMillis();
        Deque<Long> t = data.obj(k("t"));
        if (t == null) { t = new ArrayDeque<>(); data.setObj(k("t"), t); }
        t.addLast(now);
        while (!t.isEmpty() && now - t.peekFirst() > 1500) t.removeFirst();

        long last = data.getLong(k("last"));
        data.setLong(k("last"), now);
        if (last != 0 && now - last < 120) {
            int s = data.incInt(k("s"));
            if (s >= 4) {
                fail(data, player, "rapid use gap=" + (now - last) + "ms streak=" + s);
                data.setInt(k("s"), 0);
            }
        } else {
            data.setInt(k("s"), 0);
        }

        if (t.size() >= 8) {
            List<Long> iv = new ArrayList<>();
            Long p = null;
            for (Long v : t) { if (p != null) iv.add(v - p); p = v; }
            if (MathUtil.variance(iv) < 30 && MathUtil.average(iv) < 90) {
                fail(data, player, "machine use var=" + (int) MathUtil.variance(iv));
                t.clear();
            }
        }
    }
}
