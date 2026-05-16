package com.koalaguard.checks.player;

import com.koalaguard.KoalaGuard;
import com.koalaguard.check.CheckCategory;
import com.koalaguard.check.ListenerCheck;
import com.koalaguard.data.PlayerData;
import com.koalaguard.util.MathUtil;
import org.bukkit.entity.Player;
import org.bukkit.entity.ThrownExpBottle;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.entity.ProjectileLaunchEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * ExpThrower detection — throwing bottles o' enchanting at a sub-tick rate
 * or with machine-regular intervals (auto-repair).
 */
public final class ExpThrowerCheck extends ListenerCheck {

    public ExpThrowerCheck(KoalaGuard plugin) {
        super(plugin, "expthrower", CheckCategory.PLAYER, "Automated XP-bottle throwing");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onLaunch(ProjectileLaunchEvent event) {
        if (!isEnabled()) return;
        if (!(event.getEntity() instanceof ThrownExpBottle bottle)) return;
        if (!(bottle.getShooter() instanceof Player player)) return;
        if (isExempt(player)) return;
        PlayerData data = plugin.getDataManager().get(player);
        if (data == null) return;

        long now = System.currentTimeMillis();
        long last = data.getLong(k("last"));
        data.setLong(k("last"), now);

        List<Long> iv = data.obj(k("iv"));
        if (iv == null) { iv = new ArrayList<>(); data.setObj(k("iv"), iv); }
        if (last != 0) {
            long gap = now - last;
            iv.add(gap);
            if (iv.size() > 10) iv.remove(0);

            if (gap < 55) {
                int s = data.incInt(k("s"));
                if (s >= 4) {
                    fail(data, player, "throw gap=" + gap + "ms streak=" + s);
                    data.setInt(k("s"), 0);
                    iv.clear();
                    return;
                }
            } else {
                data.setInt(k("s"), 0);
            }
            if (iv.size() >= 8 && MathUtil.variance(iv) < 30 && MathUtil.average(iv) < 70) {
                fail(data, player, "machine throw var=" + (int) MathUtil.variance(iv));
                iv.clear();
            }
        }
    }
}
