package com.koalaguard.checks.world;

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

/**
 * FastBreak — packet model. Same source as SpeedMine (TRUE START_DIGGING
 * packet → BlockBreakEvent) but a stricter 35% threshold, targeting the
 * instant-1-tick packet exploit.
 */
public final class FastBreakCheck extends ListenerCheck {

    public FastBreakCheck(KoalaGuard plugin) {
        super(plugin, "fastbreak", CheckCategory.WORLD, "Instant block breaking via packets");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (!isEnabled()) return;
        Player player = event.getPlayer();
        if (isExempt(player) || player.getGameMode() == GameMode.CREATIVE) return;
        PlayerData d = plugin.getDataManager().get(player);
        if (d == null) return;

        long start = d.lastDiggingStartMs;
        if (start == 0) return;
        long elapsed = System.currentTimeMillis() - start;

        long min = BreakTimeUtil.minBreakMs(event.getBlock(), player);
        if (min <= 50) return;

        if (elapsed < min * 0.35) {
            int s = d.incInt(k("s"));
            if (s >= 2) {
                fail(d, player, String.format("%s in %dms (min %dms) streak=%d",
                        event.getBlock().getType(), elapsed, min, s));
                d.setInt(k("s"), 0);
            }
        } else {
            d.setInt(k("s"), 0);
        }
    }
}
