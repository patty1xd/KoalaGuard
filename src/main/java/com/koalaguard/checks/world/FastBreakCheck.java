package com.koalaguard.checks.world;

import com.koalaguard.KoalaGuard;
import com.koalaguard.checks.Check;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDamageEvent;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class FastBreakCheck extends Check {

    private final Map<UUID, Long> startDamage = new HashMap<>();

    public FastBreakCheck(KoalaGuard plugin) { super(plugin, "fastbreak"); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(BlockDamageEvent event) {
        startDamage.put(event.getPlayer().getUniqueId(), System.currentTimeMillis());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (!isEnabled()) return;
        Player player = event.getPlayer();
        if (player.hasPermission("koalaguard.bypass")) return;
        if (player.getGameMode() == GameMode.CREATIVE) return;

        UUID uuid = player.getUniqueId();
        Long start = startDamage.remove(uuid);
        if (start == null) return;

        long elapsed = System.currentTimeMillis() - start;
        long minBreak = estimateMinBreakTime(event.getBlock().getType(), player);

        if (elapsed < minBreak * 0.4) {
            flag(player, "elapsed=" + elapsed + "ms min=" + minBreak + "ms block=" + event.getBlock().getType());
        }
    }

    private long estimateMinBreakTime(Material mat, Player player) {
        long base = switch (mat) {
            case STONE, COBBLESTONE -> 1500;
            case OBSIDIAN -> 9000;
            case DIRT, GRAVEL -> 750;
            case OAK_LOG, BIRCH_LOG, SPRUCE_LOG, JUNGLE_LOG, ACACIA_LOG, DARK_OAK_LOG -> 1500;
            case IRON_ORE -> 2250;
            default -> 500;
        };
        if (player.hasPotionEffect(PotionEffectType.HASTE)) {
            int amp = player.getPotionEffect(PotionEffectType.HASTE).getAmplifier();
            base = (long) (base / (1.0 + 0.2 * (amp + 1)));
        }
        ItemStack tool = player.getInventory().getItemInMainHand();
        int eff = tool.getEnchantmentLevel(Enchantment.EFFICIENCY);
        if (eff > 0) base = (long) (base / (1.0 + eff * 0.3));
        return Math.max(base, 50);
    }
}
