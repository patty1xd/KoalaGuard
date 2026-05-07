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
        if (isExempt(player)) return;
        if (plugin.shouldSuppressFlags(player)) return;

        if (!event.getItem().getType().isEdible()) return;

        UUID uuid = player.getUniqueId();
        long now = System.currentTimeMillis();
        long last = lastEat.getOrDefault(uuid, 0L);

        // Don't flag for eating at full hunger (golden apples, gapples, etc. are legit).

        // Eating too fast (food use time is ~1.6s)
        if (now - last < 1300 && last != 0) {
            flag(player, "eat_interval=" + (now - last) + "ms");
        }

        lastEat.put(uuid, now);
    }
}
