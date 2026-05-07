package com.koalaguard.checks.player;

import com.koalaguard.KoalaGuard;
import com.koalaguard.checks.Check;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffectType;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class SpeedMineCheck extends Check {

    private final Map<UUID, Long> lastBreak = new HashMap<>();

    public SpeedMineCheck(KoalaGuard plugin) { super(plugin, "speedmine"); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (!isEnabled()) return;
        Player player = event.getPlayer();
        if (player.hasPermission("koalaguard.bypass")) return;
        if (player.getGameMode() == GameMode.CREATIVE) return;

        UUID uuid = player.getUniqueId();
        long now = System.currentTimeMillis();
        long last = lastBreak.getOrDefault(uuid, 0L);
        lastBreak.put(uuid, now);

        if (last == 0) return;

        // Estimate minimum break time for this block type
        long minBreakMs = estimateMinBreakTime(event.getBlock(), player);

        if (now - last < minBreakMs * 0.5) { // allow 50% tolerance
            flag(player, "break_time=" + (now - last) + "ms min=" + minBreakMs + "ms block=" + event.getBlock().getType());
        }
    }

    private long estimateMinBreakTime(Block block, Player player) {
        // Very rough estimates in ms for common blocks
        Material mat = block.getType();
        long base = switch (mat) {
            case STONE, COBBLESTONE, STONE_BRICKS -> 1500;
            case OBSIDIAN -> 9000;
            case DIRT, GRAVEL, SAND -> 750;
            case WOOD, OAK_LOG, BIRCH_LOG, SPRUCE_LOG, JUNGLE_LOG, ACACIA_LOG, DARK_OAK_LOG -> 1500;
            case IRON_ORE, DEEPSLATE_IRON_ORE -> 2250;
            case DIAMOND_ORE, DEEPSLATE_DIAMOND_ORE -> 2250;
            default -> 500;
        };

        // Reduce for haste
        if (player.hasPotionEffect(PotionEffectType.HASTE)) {
            int amp = player.getPotionEffect(PotionEffectType.HASTE).getAmplifier();
            base = (long) (base / (1.0 + 0.2 * (amp + 1)));
        }

        // Reduce for efficiency
        ItemStack tool = player.getInventory().getItemInMainHand();
        int eff = tool.getEnchantmentLevel(Enchantment.EFFICIENCY);
        if (eff > 0) base = (long) (base / (1.0 + eff * 0.3));

        return Math.max(base, 50);
    }
}
