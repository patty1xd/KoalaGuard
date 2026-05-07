package com.koalaguard.checks.player;

import com.koalaguard.KoalaGuard;
import com.koalaguard.checks.Check;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.PlayerMoveEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class AntiHungerCheck extends Check {

    private final Map<UUID, Integer> lastFoodLevel = new HashMap<>();
    private final Map<UUID, Long> lastCheck = new HashMap<>();

    public AntiHungerCheck(KoalaGuard plugin) { super(plugin, "antihunger"); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (!isEnabled()) return;
        Player player = event.getPlayer();
        if (isExempt(player)) return;
        if (plugin.shouldSuppressFlags(player)) return;

        UUID uuid = player.getUniqueId();
        long now = System.currentTimeMillis();
        long last = lastCheck.getOrDefault(uuid, 0L);

        // Check every 30 seconds
        if (now - last < 30000) return;
        lastCheck.put(uuid, now);

        int prevFood = lastFoodLevel.getOrDefault(uuid, player.getFoodLevel());
        int currFood = player.getFoodLevel();
        lastFoodLevel.put(uuid, currFood);

        // Extremely conservative: only flag if they are sprinting AND jumping (more hunger drain)
        if (prevFood == 20 && currFood == 20 && player.isSprinting() && !player.isOnGround()) {
            flag(player, "food_unchanged_while_sprinting");
        }
    }
}
