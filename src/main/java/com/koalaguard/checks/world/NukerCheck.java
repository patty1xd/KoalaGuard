package com.koalaguard.checks.world;

import com.koalaguard.KoalaGuard;
import com.koalaguard.check.CheckCategory;
import com.koalaguard.check.ListenerCheck;
import com.koalaguard.data.PlayerData;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.BlockBreakEvent;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Nuker detection — breaking many blocks per second and/or breaking blocks
 * well beyond melee reach (Meteor Nuker extends both rate and range).
 */
public final class NukerCheck extends ListenerCheck {

    public NukerCheck(KoalaGuard plugin) {
        super(plugin, "nuker", CheckCategory.WORLD, "Mass / out-of-range block breaking");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (!isEnabled()) return;
        Player player = event.getPlayer();
        if (isExempt(player) || player.getGameMode() == GameMode.CREATIVE) return;
        PlayerData data = plugin.getDataManager().get(player);
        if (data == null) return;

        Location eye = player.getEyeLocation();
        Location c = event.getBlock().getLocation().add(0.5, 0.5, 0.5);
        double dist = eye.distance(c);
        if (dist > cfgD("max-distance", 6.0)) {
            fail(data, player, String.format("break dist=%.2f", dist));
        }

        long now = System.currentTimeMillis();
        Deque<Long> t = data.obj(k("t"));
        if (t == null) { t = new ArrayDeque<>(); data.setObj(k("t"), t); }
        t.addLast(now);
        while (!t.isEmpty() && now - t.peekFirst() > 1000) t.removeFirst();
        if (t.size() > cfgI("max-breaks-per-sec", 8)) {
            fail(data, player, "break rate=" + t.size() + "/s");
            t.clear();
        }
    }
}
