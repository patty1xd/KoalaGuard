package com.koalaguard.checks.world;

import com.koalaguard.KoalaGuard;
import com.koalaguard.checks.Check;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.BlockBreakEvent;

import java.util.*;

public class VeinMinerCheck extends Check {

    private static final Set<Material> ORE_TYPES = EnumSet.of(
            Material.COAL_ORE, Material.IRON_ORE, Material.GOLD_ORE, Material.DIAMOND_ORE,
            Material.EMERALD_ORE, Material.LAPIS_ORE, Material.REDSTONE_ORE,
            Material.DEEPSLATE_COAL_ORE, Material.DEEPSLATE_IRON_ORE, Material.DEEPSLATE_GOLD_ORE,
            Material.DEEPSLATE_DIAMOND_ORE, Material.DEEPSLATE_EMERALD_ORE,
            Material.DEEPSLATE_LAPIS_ORE, Material.DEEPSLATE_REDSTONE_ORE,
            Material.COPPER_ORE, Material.DEEPSLATE_COPPER_ORE
    );

    private final Map<UUID, List<Long>> oreBreakTimes = new HashMap<>();

    public VeinMinerCheck(KoalaGuard plugin) { super(plugin, "veinminer"); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (!isEnabled()) return;
        Player player = event.getPlayer();
        if (player.hasPermission("koalaguard.bypass")) return;
        if (player.getGameMode() == GameMode.CREATIVE) return;
        if (!ORE_TYPES.contains(event.getBlock().getType())) return;

        UUID uuid = player.getUniqueId();
        long now = System.currentTimeMillis();

        List<Long> times = oreBreakTimes.computeIfAbsent(uuid, k -> new ArrayList<>());
        times.add(now);
        times.removeIf(t -> now - t > 2000); // 2s window

        // Breaking 5+ ores within 2 seconds = veinminer
        if (times.size() >= 5) {
            flag(player, "ores_in_2s=" + times.size());
            times.clear();
        }
    }
}
