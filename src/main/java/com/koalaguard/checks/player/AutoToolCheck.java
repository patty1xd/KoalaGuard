package com.koalaguard.checks.player;

import com.koalaguard.KoalaGuard;
import com.koalaguard.check.CheckCategory;
import com.koalaguard.check.ListenerCheck;
import com.koalaguard.data.PlayerData;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.BlockDamageEvent;

/**
 * AutoTool — packet model. Compares the TRUE held-slot-change packet time to
 * the TRUE START_DIGGING packet time. AutoTool swaps to the optimal tool the
 * instant digging begins; a human cannot select-then-dig within one tick.
 */
public final class AutoToolCheck extends ListenerCheck {

    public AutoToolCheck(KoalaGuard plugin) {
        super(plugin, "autotool", CheckCategory.PLAYER, "Auto-switching to the optimal tool on dig");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDig(BlockDamageEvent event) {
        if (!isEnabled()) return;
        Player player = event.getPlayer();
        if (isExempt(player)) return;
        PlayerData d = plugin.getDataManager().get(player);
        if (d == null) return;

        long slot = d.lastSlotChangeMs;
        long dig = d.lastDiggingStartMs;
        if (slot == 0 || dig == 0) return;

        long gap = Math.abs(dig - slot);
        if (gap < 55) {
            int s = d.incInt(k("s"));
            if (s >= 3) {
                fail(d, player, "switch↔dig " + gap + "ms streak=" + s);
                d.setInt(k("s"), 0);
            }
        } else {
            d.setInt(k("s"), 0);
        }
    }
}
