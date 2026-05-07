package com.koalaguard.checks.player;

import com.koalaguard.KoalaGuard;
import com.koalaguard.checks.Check;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.PlayerFishEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class AutoFishCheck extends Check {

    private final Map<UUID, Long> lastBite = new HashMap<>();
    private final Map<UUID, Long> lastCatch = new HashMap<>();

    public AutoFishCheck(KoalaGuard plugin) { super(plugin, "autofish"); }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onFish(PlayerFishEvent event) {
        if (!isEnabled()) return;
        Player player = event.getPlayer();
        if (isExempt(player)) return;
        if (plugin.shouldSuppressFlags(player)) return;

        UUID uuid = player.getUniqueId();
        long now = System.currentTimeMillis();

        if (event.getState() == PlayerFishEvent.State.BITE) {
            lastBite.put(uuid, now);
        } else if (event.getState() == PlayerFishEvent.State.CAUGHT_FISH) {
            long bite = lastBite.getOrDefault(uuid, 0L);
            if (bite != 0) {
                long reactionTime = now - bite;
                // Conservative: only flag for extremely fast reaction
                if (reactionTime < 60) {
                    flag(player, "reaction=" + reactionTime + "ms");
                }
                lastBite.remove(uuid);
            }
            lastCatch.put(uuid, now);
        }
    }
}
