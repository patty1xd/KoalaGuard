package com.koalaguard.checks.player;

import com.koalaguard.KoalaGuard;
import com.koalaguard.check.CheckCategory;
import com.koalaguard.check.ListenerCheck;
import com.koalaguard.data.PlayerData;
import com.koalaguard.util.BreakTimeUtil;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDamageEvent;

/**
 * SpeedMine detection — breaking a block well below its hardness-derived
 * minimum break time (accounts for tool, Efficiency, Haste, Fatigue).
 */
public final class SpeedMineCheck extends ListenerCheck {

    public SpeedMineCheck(KoalaGuard plugin) {
        super(plugin, "speedmine", CheckCategory.PLAYER, "Breaking blocks faster than hardness allows");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onStart(BlockDamageEvent event) {
        if (event.getPlayer().getGameMode() == GameMode.CREATIVE) return;
        PlayerData d = plugin.getDataManager().get(event.getPlayer());
        if (d != null && d.getLong(k("start")) == 0) d.setLong(k("start"), System.currentTimeMillis());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (!isEnabled()) return;
        Player player = event.getPlayer();
        if (isExempt(player) || player.getGameMode() == GameMode.CREATIVE) return;
        PlayerData data = plugin.getDataManager().get(player);
        if (data == null) return;

        long start = data.getLong(k("start"));
        data.setLong(k("start"), 0);
        if (start == 0) return;

        long elapsed = System.currentTimeMillis() - start;
        long min = BreakTimeUtil.minBreakMs(event.getBlock(), player);
        if (min <= 50) return;

        if (elapsed < min * 0.40) {
            int s = data.incInt(k("s"));
            if (s >= 2) {
                fail(data, player, String.format("%s in %dms (min %dms) streak=%d",
                        event.getBlock().getType(), elapsed, min, s));
                data.setInt(k("s"), 0);
            }
        } else {
            data.setInt(k("s"), 0);
        }
    }
}
