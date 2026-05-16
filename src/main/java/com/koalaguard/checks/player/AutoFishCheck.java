package com.koalaguard.checks.player;

import com.koalaguard.KoalaGuard;
import com.koalaguard.check.CheckCategory;
import com.koalaguard.check.ListenerCheck;
import com.koalaguard.data.PlayerData;
import com.koalaguard.util.MathUtil;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.PlayerFishEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * AutoFish detection — reeling within sub-human reaction time of the bite,
 * or with machine-consistent reaction times across many catches.
 */
public final class AutoFishCheck extends ListenerCheck {

    public AutoFishCheck(KoalaGuard plugin) {
        super(plugin, "autofish", CheckCategory.PLAYER, "Auto-fishing (instant bite reaction)");
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onFish(PlayerFishEvent event) {
        if (!isEnabled()) return;
        Player player = event.getPlayer();
        if (isExempt(player)) return;
        PlayerData data = plugin.getDataManager().get(player);
        if (data == null) return;

        long now = System.currentTimeMillis();
        if (event.getState() == PlayerFishEvent.State.BITE) {
            data.setLong(k("bite"), now);
        } else if (event.getState() == PlayerFishEvent.State.CAUGHT_FISH) {
            long bite = data.getLong(k("bite"));
            data.setLong(k("bite"), 0);
            if (bite == 0) return;
            long reaction = now - bite;

            if (reaction < 100) {
                int s = data.incInt(k("s"));
                if (s >= 2) {
                    fail(data, player, "reaction=" + reaction + "ms streak=" + s);
                    data.setInt(k("s"), 0);
                }
            } else {
                data.setInt(k("s"), 0);
            }

            List<Long> rt = data.obj(k("rt"));
            if (rt == null) { rt = new ArrayList<>(); data.setObj(k("rt"), rt); }
            rt.add(reaction);
            if (rt.size() > 10) rt.remove(0);
            if (rt.size() >= 5 && MathUtil.variance(rt) < 30 && MathUtil.average(rt) < 90) {
                fail(data, player, String.format("consistent reaction mean=%.0fms var=%.1f",
                        MathUtil.average(rt), MathUtil.variance(rt)));
                rt.clear();
            }
        }
    }
}
