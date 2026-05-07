package com.koalaguard.checks.player;

import com.koalaguard.KoalaGuard;
import com.koalaguard.checks.Check;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerMoveEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class AutoEatCheck extends Check {

    private final Map<UUID, Long> lastEat = new HashMap<>();

    public AutoEatCheck(KoalaGuard plugin) { super(plugin, "autoeat"); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onConsume(PlayerItemConsumeEvent event) {
        if (!isEnabled()) return;
        Player player = event.getPlayer();
        if (player.hasPermission("koalaguard.bypass")) return;

        if (!event.getItem().getType().isEdible()) return;

        UUID uuid = player.getUniqueId();
        long now = System.currentTimeMillis();
        long last = lastEat.getOrDefault(uuid, 0L);

        // AutoEat: eating while moving at speed, or eating when hunger is full
        if (player.getFoodLevel() >= 19) {
            flag(player, "ate_at_full_hunger=" + player.getFoodLevel());
        }

        // Eating too fast (food use time is ~32 ticks = 1.6s)
        if (now - last < 1500 && last != 0) {
            flag(player, "eat_interval=" + (now - last) + "ms");
        }

        lastEat.put(uuid, now);
    }
}
