package com.koalaguard.checks.movement;

import com.koalaguard.KoalaGuard;
import com.koalaguard.checks.Check;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffectType;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Detects Meteor NoSlow.
 *
 * From Meteor's source (NoSlow.java):
 *   - Cancels the USE_ITEM action packet slowdown:
 *     while eating, charging a bow, or blocking with a shield, vanilla
 *     applies a 0.2× speed multiplier to the player's movement.
 *   - Meteor sends a RELEASE_USE_ITEM packet each tick to bypass this,
 *     then re-sends START_USE_ITEM — the server never applies the slowdown.
 *   - Also covers sneaking-while-blocking (shield) and item-using speed.
 *
 * Vanilla slowdown multipliers (applied to walk speed while using item):
 *   Eating / drinking: 0.2×
 *   Charging bow:      0.2×
 *   Using shield:      0.2× (blocking)
 *   Expected max horizontal speed while using item ≈ sprint × 0.2 ≈ 0.065 bl/tick
 *
 * Detection:
 *   While the player is actively using an item (bow, food, potion, shield),
 *   measure horizontal speed. If it exceeds 0.18 bl/tick (well above the
 *   slowdown maximum, below normal sprint to avoid FPs), flag.
 *   Require streak of 5 consecutive fast-while-using ticks.
 */
public class NoSlowCheck extends Check {

    // Items that cause the server-side slowdown
    private static final double SLOW_SPEED_MAX   = 0.18; // bl/tick while using item
    private static final int    STREAK_THRESHOLD = 5;

    private final Map<UUID, Integer> fastUseStreak = new HashMap<>();
    private final Map<UUID, Long>    usingItemSince = new HashMap<>();

    public NoSlowCheck(KoalaGuard plugin) { super(plugin, "noslow"); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (!isEnabled()) return;
        Player player = event.getPlayer();
        if (isExempt(player)) return;
        if (plugin.shouldSuppressFlags(player)) return;
        if (event.getTo() == null) return;

        if (player.isFlying() || player.getAllowFlight()) return;
        if (player.isInsideVehicle()) return;
        if (player.isInWater() || player.isInLava()) { fastUseStreak.put(player.getUniqueId(), 0); return; }

        // Speed potion increases the effective max even while using items — be generous
        if (player.hasPotionEffect(PotionEffectType.SPEED)) return;

        UUID uuid = player.getUniqueId();

        // Check if player is currently using an item that causes slowdown
        ItemStack active = player.getActiveItem();
        if (active == null || active.getType() == Material.AIR || !isSlowindItem(active)) {
            fastUseStreak.put(uuid, 0);
            usingItemSince.remove(uuid);
            return;
        }

        // Require item usage for at least 3 ticks (150ms) before checking
        // (avoids FPs from first-tick of item use where server hasn't applied slow)
        long usingSince = usingItemSince.computeIfAbsent(uuid, k -> System.currentTimeMillis());
        if (System.currentTimeMillis() - usingSince < 150) return;

        double dx = event.getTo().getX() - event.getFrom().getX();
        double dz = event.getTo().getZ() - event.getFrom().getZ();
        double h  = Math.sqrt(dx * dx + dz * dz);

        if (h > SLOW_SPEED_MAX) {
            int s = fastUseStreak.merge(uuid, 1, Integer::sum);
            if (s >= STREAK_THRESHOLD) {
                flag(player, String.format("noslow item=%s h=%.3f max=%.3f streak=%d",
                        active.getType().name(), h, SLOW_SPEED_MAX, s));
                fastUseStreak.put(uuid, 0);
                usingItemSince.remove(uuid);
            }
        } else {
            fastUseStreak.put(uuid, 0);
        }
    }

    // Reset usage tracking when item finishes being used
    @EventHandler(priority = EventPriority.MONITOR)
    public void onConsume(PlayerItemConsumeEvent event) {
        usingItemSince.remove(event.getPlayer().getUniqueId());
        fastUseStreak.put(event.getPlayer().getUniqueId(), 0);
    }

    private boolean isSlowindItem(ItemStack item) {
        Material mat = item.getType();
        String name  = mat.name();
        // Food, potions
        if (mat.isEdible()) return true;
        if (name.contains("POTION")) return true;
        // Bow / crossbow
        if (mat == Material.BOW || mat == Material.CROSSBOW) return true;
        // Shield
        if (mat == Material.SHIELD) return true;
        // Trident (charging)
        if (mat == Material.TRIDENT) return true;
        return false;
    }
}
