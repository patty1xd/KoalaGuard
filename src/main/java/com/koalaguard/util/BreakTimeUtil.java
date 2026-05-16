package com.koalaguard.util;

import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffectType;

/**
 * Vanilla block break-time estimate (ms) from hardness, the held tool,
 * Efficiency, Haste and Mining Fatigue. Returns -1 for unbreakable.
 */
public final class BreakTimeUtil {

    private BreakTimeUtil() {}

    public static long minBreakMs(Block block, Player player) {
        float hardness = block.getType().getHardness();
        if (hardness < 0) return -1;
        if (hardness == 0) return 0;

        ItemStack tool = player.getInventory().getItemInMainHand();
        boolean correct = correctTool(block.getType(), tool);
        double ticks = Math.ceil(hardness * (correct ? 1.5 : 5.0) * 20.0);

        double speed = 1.0;
        int eff = tool.getEnchantmentLevel(Enchantment.EFFICIENCY);
        if (eff > 0 && correct) speed += (eff * eff + 1.0) / (ticks > 0 ? ticks : 1);

        if (player.hasPotionEffect(PotionEffectType.HASTE)) {
            int amp = player.getPotionEffect(PotionEffectType.HASTE).getAmplifier();
            speed *= 1.0 + 0.2 * (amp + 1);
        }
        if (player.hasPotionEffect(PotionEffectType.MINING_FATIGUE)) {
            int amp = player.getPotionEffect(PotionEffectType.MINING_FATIGUE).getAmplifier();
            speed *= Math.pow(0.3, Math.min(amp + 1, 4));
        }
        return Math.max((long) ((ticks / Math.max(speed, 0.01)) * 50), 50);
    }

    private static boolean correctTool(Material block, ItemStack tool) {
        String b = block.name();
        String t = tool.getType().name();
        if (b.contains("ORE") || b.contains("STONE") || b.contains("COBBLESTONE")
                || b.contains("GRANITE") || b.contains("DIORITE") || b.contains("ANDESITE")
                || b.contains("DEEPSLATE") || b.contains("OBSIDIAN") || b.contains("NETHERRACK")
                || b.contains("BRICK") || b.contains("CONCRETE"))
            return t.contains("PICKAXE");
        if (b.contains("LOG") || b.contains("PLANKS") || b.contains("WOOD")
                || b.contains("FENCE") || b.contains("DOOR"))
            return t.contains("AXE");
        if (b.contains("DIRT") || b.contains("SAND") || b.contains("GRAVEL")
                || b.contains("CLAY") || b.contains("GRASS") || b.contains("SOUL")
                || b.contains("SNOW") || b.contains("MUD"))
            return t.contains("SHOVEL");
        return false;
    }
}
