package com.koalaguard.checks.world;

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
import org.bukkit.event.block.BlockDamageEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffectType;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Detects Meteor SpeedMine "FastBreak" sub-mode.
 *
 * From Meteor's source:
 *   - "Damage" mode: sends repeated BlockDamage packets far faster than
 *     the block's actual hardness allows, completing the break in < 1 tick.
 *   - The key server-side signal: time from first damage packet (start mining)
 *     to BlockBreakEvent is much shorter than the minimum computed break time.
 *
 * This check is distinct from SpeedMineCheck in that it uses block hardness
 * from the Bukkit API for precise timing rather than categorical estimates.
 * It applies a 35% threshold (flag if elapsed < 35% of minimum break time).
 *
 * Require streak of 2 before flagging.
 */
public class FastBreakCheck extends Check {

    private final Map<UUID, Long>    startMs    = new HashMap<>();
    private final Map<UUID, Integer> fastStreak = new HashMap<>();

    public FastBreakCheck(KoalaGuard plugin) { super(plugin, "fastbreak"); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamageStart(BlockDamageEvent event) {
        Player player = event.getPlayer();
        if (player.getGameMode() == GameMode.CREATIVE) return;
        // Only record the first damage (don't reset on each hit)
        startMs.putIfAbsent(player.getUniqueId(), System.currentTimeMillis());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (!isEnabled()) return;
        Player player = event.getPlayer();
        if (isExempt(player)) return;
        if (plugin.shouldSuppressFlags(player)) return;
        if (player.getGameMode() == GameMode.CREATIVE) return;

        UUID uuid  = player.getUniqueId();
        Long start = startMs.remove(uuid);
        if (start == null) return;

        long elapsed = System.currentTimeMillis() - start;
        long minMs   = computeMinBreakMs(event.getBlock(), player);
        if (minMs <= 0) return;

        if (elapsed < minMs * 0.35) {
            int s = fastStreak.merge(uuid, 1, Integer::sum);
            if (s >= 2) {
                flag(player, String.format("fastbreak elapsed=%dms min=%dms block=%s streak=%d",
                        elapsed, minMs, event.getBlock().getType().name(), s));
                fastStreak.put(uuid, 0);
            }
        } else {
            fastStreak.put(uuid, 0);
        }
    }

    private long computeMinBreakMs(Block block, Player player) {
        float hardness = block.getType().getHardness();
        if (hardness < 0) return -1;
        if (hardness == 0) return 0;

        ItemStack tool = player.getInventory().getItemInMainHand();
        boolean correct = isCorrectTool(block.getType(), tool);
        double ticks = Math.ceil(hardness * (correct ? 1.5 : 5.0) * 20.0);

        int eff = tool.getEnchantmentLevel(Enchantment.EFFICIENCY);
        double speed = 1.0;
        if (eff > 0 && correct) speed += (eff * eff + 1.0) / (ticks > 0 ? ticks : 1);

        if (player.hasPotionEffect(PotionEffectType.HASTE)) {
            int amp = player.getPotionEffect(PotionEffectType.HASTE).getAmplifier();
            speed *= 1.0 + 0.2 * (amp + 1);
        }
        if (player.hasPotionEffect(PotionEffectType.MINING_FATIGUE)) {
            int amp = player.getPotionEffect(PotionEffectType.MINING_FATIGUE).getAmplifier();
            speed *= Math.pow(0.3, Math.min(amp + 1, 4));
        }

        return Math.max((long)((ticks / Math.max(speed, 0.01)) * 50), 50);
    }

    private boolean isCorrectTool(Material block, ItemStack tool) {
        String bn = block.name(), tn = tool.getType().name();
        if (bn.contains("ORE") || bn.contains("STONE") || bn.contains("COBBLESTONE")
                || bn.contains("GRANITE") || bn.contains("DIORITE") || bn.contains("ANDESITE")
                || bn.contains("DEEPSLATE") || bn.contains("OBSIDIAN") || bn.contains("NETHERRACK"))
            return tn.contains("PICKAXE");
        if (bn.contains("LOG") || bn.contains("PLANKS") || bn.contains("WOOD"))
            return tn.contains("AXE");
        if (bn.contains("DIRT") || bn.contains("SAND") || bn.contains("GRAVEL")
                || bn.contains("CLAY") || bn.contains("GRASS"))
            return tn.contains("SHOVEL");
        return false;
    }
}
