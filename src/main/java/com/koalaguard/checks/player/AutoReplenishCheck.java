package com.koalaguard.checks.player;

import com.koalaguard.KoalaGuard;
import com.koalaguard.checks.Check;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.inventory.ItemStack;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Detects Meteor InvManager / AutoReplenish.
 *
 * From Meteor's source (InvManager.java):
 *   - Monitors hotbar item counts; when a stack runs low (or reaches 1),
 *     automatically moves a matching stack from inventory to the hotbar slot.
 *   - This happens within 0–1 ticks of the slot being selected.
 *   - Observable: item count in the active hotbar slot jumps UP on the SAME
 *     slot visit without any inventory interaction from the player.
 *
 * Detection:
 *   Track item count when a slot is first visited (PlayerItemHeldEvent).
 *   On the NEXT visit to the same slot: if count increased without any
 *   inventory open event in between AND the increase happened within 100 ms,
 *   that's an automated replenish.
 *   Require 3 consecutive such replenish detections before flagging.
 */
public class AutoReplenishCheck extends Check {

    private final Map<UUID, Integer> prevCount     = new HashMap<>();
    private final Map<UUID, Long>    prevVisitMs   = new HashMap<>();
    private final Map<UUID, Integer> prevSlot      = new HashMap<>();
    private final Map<UUID, Integer> replenStreak  = new HashMap<>();

    private static final long REPLENISH_WINDOW_MS = 150L;

    public AutoReplenishCheck(KoalaGuard plugin) { super(plugin, "autoreplenish"); }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onHeldChange(PlayerItemHeldEvent event) {
        if (!isEnabled()) return;
        Player player = event.getPlayer();
        if (isExempt(player)) return;
        if (plugin.shouldSuppressFlags(player)) return;

        UUID uuid    = player.getUniqueId();
        long now     = System.currentTimeMillis();
        int  newSlot = event.getNewSlot();
        int  oldSlot = event.getPreviousSlot();

        // Record count of the slot we're LEAVING
        ItemStack leaving = player.getInventory().getItem(oldSlot);
        int leavingCount  = leaving != null ? leaving.getAmount() : 0;

        // Check if count of the slot we're RETURNING TO jumped
        Integer lastCount = prevCount.get(uuid);
        Integer lastSlot  = prevSlot.get(uuid);
        Long    lastMs    = prevVisitMs.get(uuid);

        if (lastSlot != null && lastSlot == newSlot && lastCount != null && lastMs != null) {
            ItemStack returning = player.getInventory().getItem(newSlot);
            int returnCount = returning != null ? returning.getAmount() : 0;

            // Count jumped significantly in < REPLENISH_WINDOW_MS — automated refill
            if (returnCount > lastCount + 8 && now - lastMs < REPLENISH_WINDOW_MS) {
                int s = replenStreak.merge(uuid, 1, Integer::sum);
                if (s >= 3) {
                    flag(player, "replenish slot=" + newSlot
                            + " " + lastCount + "->" + returnCount
                            + " in " + (now - lastMs) + "ms streak=" + s);
                    replenStreak.put(uuid, 0);
                }
            } else if (returnCount <= lastCount) {
                replenStreak.put(uuid, 0);
            }
        }

        prevSlot.put(uuid, oldSlot);
        prevCount.put(uuid, leavingCount);
        prevVisitMs.put(uuid, now);
    }
}
