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
import org.bukkit.event.block.BlockDamageEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffectType;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Detects Meteor SpeedMine.
 *
 * From Meteor's source (SpeedMine.java):
 *   - "Damage" mode: sends rapid BlockDamage packets, bypassing the server's
 *     progressive damage model, so blocks break nearly instantly.
 *   - "Full" mode: re-sends START_DIGGING every tick, resetting damage to max
 *     each tick — blocks break in < 1 game tick.
 *   - Observable: BlockBreakEvent fires significantly faster than the block's
 *     actual hardness-based minimum break time.
 *
 * Vanilla break times are calculated from block hardness:
 *   breakTicks = ceil(hardness * (correct_tool ? 1.5 : 5.0) * 20)
 *
 * We use Bukkit's block hardness API (block.getType().getHardness()) for
 * accuracy instead of a hard-coded switch table.
 * Reductions applied: Haste potion (×(1 + 0.2×(amp+1))) and Efficiency.
 *
 * Flag if elapsed < 40% of computed minimum (generous: avoids Haste-II FPs).
 */
public class SpeedMineCheck extends Check {

    private final Map<UUID, Long>    breakStartMs = new HashMap<>();
    private final Map<UUID, Integer> fastStreak   = new HashMap<>();

    public SpeedMineCheck(KoalaGuard plugin) { super(plugin, "speedmine"); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamageStart(BlockDamageEvent event) {
        Player player = event.getPlayer();
        if (player.getGameMode() == GameMode.CREATIVE) return;
        breakStartMs.put(player.getUniqueId(), System.currentTimeMillis());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (!isEnabled()) return;
        Player player = event.getPlayer();
        if (isExempt(player)) return;
        if (plugin.shouldSuppressFlags(player)) return;
        if (player.getGameMode() == GameMode.CREATIVE) return;

        UUID uuid  = player.getUniqueId();
        Long start = breakStartMs.remove(uuid);
        if (start == null) return;

        long elapsed = System.currentTimeMillis() - start;
        long minMs   = computeMinBreakMs(event.getBlock(), player);
        if (minMs <= 0) return;

        if (elapsed < minMs * 0.40) {
            int s = fastStreak.merge(uuid, 1, Integer::sum);
            if (s >= 2) {
                flag(player, String.format("speedmine elapsed=%dms min=%dms block=%s streak=%d",
                        elapsed, minMs, event.getBlock().getType().name(), s));
                fastStreak.put(uuid, 0);
            }
        } else {
            fastStreak.put(uuid, 0);
        }
    }

    private long computeMinBreakMs(Block block, Player player) {
        float hardness = block.getType().getHardness();
        if (hardness < 0) return -1; // unbreakable (bedrock, etc.)
        if (hardness == 0) return 0;  // instant-break

        // Vanilla formula: break ticks = hardness × factor × 20 (using correct tool factor 1.5)
        ItemStack tool = player.getInventory().getItemInMainHand();
        boolean correctTool = isCorrectTool(block.getType(), tool);
        double factor = correctTool ? 1.5 : 5.0;
        double baseTicks = Math.ceil(hardness * factor * 20.0);

        // Efficiency enchantment: each level adds (level² + 1) extra speed
        int eff = tool.getEnchantmentLevel(Enchantment.EFFICIENCY);
        double speedMultiplier = 1.0;
        if (eff > 0 && correctTool) {
            speedMultiplier += (eff * eff + 1.0) / (baseTicks > 0 ? baseTicks : 1);
        }

        // Haste potion: ×(1 + 0.2 × (amp+1)) per vanilla formula
        if (player.hasPotionEffect(PotionEffectType.HASTE)) {
            int amp = player.getPotionEffect(PotionEffectType.HASTE).getAmplifier();
            speedMultiplier *= 1.0 + 0.2 * (amp + 1);
        }

        // Mining Fatigue reduces speed
        if (player.hasPotionEffect(PotionEffectType.MINING_FATIGUE)) {
            int amp = player.getPotionEffect(PotionEffectType.MINING_FATIGUE).getAmplifier();
            speedMultiplier *= Math.pow(0.3, Math.min(amp + 1, 4));
        }

        double ticks = baseTicks / Math.max(speedMultiplier, 0.01);
        return Math.max((long)(ticks * 50), 50); // ticks → ms, min 50ms
    }

    private boolean isCorrectTool(Material block, ItemStack tool) {
        String bname = block.name();
        String tname = tool.getType().name();
        if (bname.contains("ORE") || bname.contains("STONE") || bname.contains("COBBLESTONE")
                || bname.contains("GRANITE") || bname.contains("DIORITE") || bname.contains("ANDESITE")
                || bname.contains("DEEPSLATE") || bname.contains("OBSIDIAN") || bname.contains("NETHERRACK")) {
            return tname.contains("PICKAXE");
        }
        if (bname.contains("LOG") || bname.contains("PLANKS") || bname.contains("WOOD")) {
            return tname.contains("AXE");
        }
        if (bname.contains("DIRT") || bname.contains("SAND") || bname.contains("GRAVEL")
                || bname.contains("CLAY") || bname.contains("GRASS")) {
            return tname.contains("SHOVEL");
        }
        return false;
    }
}
