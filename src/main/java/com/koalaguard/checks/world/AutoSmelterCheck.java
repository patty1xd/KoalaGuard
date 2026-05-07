package com.koalaguard.checks.world;

import com.koalaguard.KoalaGuard;
import com.koalaguard.checks.Check;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.inventory.FurnaceExtractEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class AutoSmelterCheck extends Check {

    private final Map<UUID, Long> lastExtract = new HashMap<>();
    private final Map<UUID, Integer> extractCount = new HashMap<>();

    public AutoSmelterCheck(KoalaGuard plugin) { super(plugin, "autosmelter"); }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onExtract(FurnaceExtractEvent event) {
        if (!isEnabled()) return;
        Player player = event.getPlayer();
        if (isExempt(player)) return;
        if (plugin.shouldSuppressFlags(player)) return;

        UUID uuid = player.getUniqueId();
        long now = System.currentTimeMillis();
        long last = lastExtract.getOrDefault(uuid, 0L);

        // Very conservative to avoid FPs (server tick jitter):
        if (now - last < 35) {
            int count = extractCount.merge(uuid, 1, Integer::sum);
            if (count >= 7) {
                flag(player, "rapid_extract count=" + count);
                extractCount.put(uuid, 0);
            }
        } else {
            extractCount.put(uuid, 1);
        }
        lastExtract.put(uuid, now);
    }
}
