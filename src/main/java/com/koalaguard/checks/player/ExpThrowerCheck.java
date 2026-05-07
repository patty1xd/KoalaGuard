package com.koalaguard.checks.player;

import com.koalaguard.KoalaGuard;
import com.koalaguard.checks.Check;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.PlayerExpChangeEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class ExpThrowerCheck extends Check {

    private final Map<UUID, Long> lastExpBottle = new HashMap<>();
    private final Map<UUID, Integer> bottleCount = new HashMap<>();

    public ExpThrowerCheck(KoalaGuard plugin) { super(plugin, "expthrower"); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onConsume(PlayerItemConsumeEvent event) {
        if (!isEnabled()) return;
        Player player = event.getPlayer();
        if (isExempt(player)) return;
        if (plugin.shouldSuppressFlags(player)) return;
        if (event.getItem().getType() != Material.EXPERIENCE_BOTTLE) return;

        UUID uuid = player.getUniqueId();
        long now = System.currentTimeMillis();
        long last = lastExpBottle.getOrDefault(uuid, 0L);

        // Conservative: only flag very rapid repeated use
        if (now - last < 60) {
            int count = bottleCount.merge(uuid, 1, Integer::sum);
            if (count >= 14) {
                flag(player, "rapid_exp_bottle count=" + count);
                bottleCount.put(uuid, 0);
            }
        } else {
            bottleCount.put(uuid, 1);
        }
        lastExpBottle.put(uuid, now);
    }
}
