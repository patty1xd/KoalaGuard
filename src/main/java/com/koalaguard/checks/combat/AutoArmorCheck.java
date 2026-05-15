package com.koalaguard.checks.combat;

import com.koalaguard.KoalaGuard;
import com.koalaguard.checks.Check;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryType;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Detects Meteor AutoArmor.
 *
 * From Meteor's source (AutoArmor.java):
 *   - On each tick, scans inventory for better armor and equips it.
 *   - Uses InvUtils.move() which fires InventoryClickEvent for ARMOR slots.
 *   - Default delay: 0 ticks — all 4 pieces equip within a single server tick.
 *   - Server-side: up to 4 ARMOR-type InventoryClickEvents within ~50 ms.
 *
 * Detection:
 *   Track how many ARMOR slot clicks occur within an 80 ms window.
 *   3+ distinct armor slot clicks inside that window = impossible for a human
 *   (human minimum between individual armor slot clicks is ~150 ms).
 *   Require 2 consecutive such bursts before flagging.
 */
public class AutoArmorCheck extends Check {

    private final Map<UUID, Long>    windowStart  = new HashMap<>();
    private final Map<UUID, Integer> windowCount  = new HashMap<>();
    private final Map<UUID, Integer> burstStreak  = new HashMap<>();

    private static final long BURST_WINDOW_MS = 80L;
    private static final int  BURST_THRESHOLD = 3;

    public AutoArmorCheck(KoalaGuard plugin) { super(plugin, "autoarmor"); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!isEnabled()) return;
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (isExempt(player)) return;
        if (plugin.shouldSuppressFlags(player)) return;
        if (event.getSlotType() != InventoryType.SlotType.ARMOR) return;

        UUID uuid = player.getUniqueId();
        long now  = System.currentTimeMillis();

        long wStart = windowStart.getOrDefault(uuid, now);
        if (now - wStart > BURST_WINDOW_MS) {
            // New window: commit previous burst count
            int prev = windowCount.getOrDefault(uuid, 0);
            if (prev >= BURST_THRESHOLD) {
                int streak = burstStreak.merge(uuid, 1, Integer::sum);
                if (streak >= 2) {
                    flag(player, "armor_burst slots=" + prev + " streak=" + streak);
                    burstStreak.put(uuid, 0);
                }
            } else {
                burstStreak.put(uuid, 0);
            }
            windowStart.put(uuid, now);
            windowCount.put(uuid, 1);
        } else {
            windowCount.merge(uuid, 1, Integer::sum);
        }
    }
}
