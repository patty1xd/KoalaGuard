package com.koalaguard.checks.player;

import com.koalaguard.KoalaGuard;
import com.koalaguard.check.CheckCategory;
import com.koalaguard.check.ListenerCheck;
import com.koalaguard.data.PlayerData;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.PlayerItemConsumeEvent;

/**
 * AutoEat detection.
 *
 * Flags FastEat (consuming far below the 1.6 s vanilla minimum) and the
 * bot-tell of repeatedly eating while already at full hunger.
 */
public final class AutoEatCheck extends ListenerCheck {

    public AutoEatCheck(KoalaGuard plugin) {
        super(plugin, "autoeat", CheckCategory.PLAYER, "Auto/fast eating");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onConsume(PlayerItemConsumeEvent event) {
        if (!isEnabled()) return;
        Player player = event.getPlayer();
        if (isExempt(player) || !event.getItem().getType().isEdible()) return;
        PlayerData data = plugin.getDataManager().get(player);
        if (data == null) return;

        long now = System.currentTimeMillis();
        long last = data.getLong(k("last"));
        data.setLong(k("last"), now);

        if (last != 0 && now - last < 800) {
            int s = data.incInt(k("fast"));
            if (s >= 2) {
                fail(data, player, "fast-eat gap=" + (now - last) + "ms streak=" + s);
                data.setInt(k("fast"), 0);
            }
        } else {
            data.setInt(k("fast"), 0);
        }

        if (player.getFoodLevel() >= 20 && player.getSaturation() > 4) {
            int s = data.incInt(k("full"));
            if (s >= 3) {
                fail(data, player, "eating at full hunger streak=" + s);
                data.setInt(k("full"), 0);
            }
        } else {
            data.setInt(k("full"), 0);
        }
    }
}
