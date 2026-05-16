package com.koalaguard.checks.player;

import com.koalaguard.KoalaGuard;
import com.koalaguard.check.CheckCategory;
import com.koalaguard.check.ListenerCheck;
import com.koalaguard.data.PlayerData;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.BlockDamageEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;

/**
 * AutoTool detection — a hotbar slot switch landing within the same tick a
 * block starts being mined. Meteor AutoTool swaps to the optimal tool the
 * instant you begin digging; a human cannot select-then-dig that fast.
 */
public final class AutoToolCheck extends ListenerCheck {

    public AutoToolCheck(KoalaGuard plugin) {
        super(plugin, "autotool", CheckCategory.PLAYER, "Auto-switching to the optimal tool on dig");
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onSlot(PlayerItemHeldEvent event) {
        PlayerData d = plugin.getDataManager().get(event.getPlayer());
        if (d != null) d.setLong(k("slot"), System.currentTimeMillis());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDig(BlockDamageEvent event) {
        if (!isEnabled()) return;
        Player player = event.getPlayer();
        if (isExempt(player)) return;
        PlayerData data = plugin.getDataManager().get(player);
        if (data == null) return;

        long slot = data.getLong(k("slot"));
        if (slot == 0) return;
        long gap = System.currentTimeMillis() - slot;
        if (gap < 55) {
            int s = data.incInt(k("s"));
            if (s >= 3) {
                fail(data, player, "switch→dig " + gap + "ms streak=" + s);
                data.setInt(k("s"), 0);
            }
        } else {
            data.setInt(k("s"), 0);
        }
    }
}
