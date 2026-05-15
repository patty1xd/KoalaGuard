package com.koalaguard.checks.combat;

import com.koalaguard.KoalaGuard;
import com.koalaguard.checks.Check;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.entity.EntityResurrectEvent;
import org.bukkit.inventory.ItemStack;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Detects Meteor AutoTotem.
 *
 * From Meteor's source code:
 *   - Listens for ClientboundEntityEventPacket with PROTECTED_FROM_DEATH (= totem consumed).
 *   - Default delay: 0 ticks. Immediately calls InvUtils.move() to offhand on the NEXT tick.
 *   - Server-side: offhand changes to a new totem within 0–1 ticks (0–50 ms) of resurrect.
 *
 * Detection:
 *   After EntityResurrectEvent fires, schedule a check 1 tick later.
 *   If the offhand slot is now a TOTEM_OF_UNDYING (and wasn't before),
 *   the swap happened in ≤1 tick — impossible for a human.
 *   Require 2 consecutive instances before flagging.
 *
 * Human realistic: 200–500 ms to notice the totem was consumed and manually re-equip.
 * With AutoTotem default delay=0: re-equip happens the very next server tick (≤50 ms).
 */
public class AutoTotemCheck extends Check {

    // Timestamp of the resurrect event per player
    private final Map<UUID, Long>    resurrectMs = new HashMap<>();
    // How many times they've re-equipped suspiciously fast
    private final Map<UUID, Integer> fastSwapStreak = new HashMap<>();

    public AutoTotemCheck(KoalaGuard plugin) { super(plugin, "autototem"); }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onResurrect(EntityResurrectEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        if (isExempt(player)) return;

        UUID uuid = player.getUniqueId();
        resurrectMs.put(uuid, System.currentTimeMillis());

        // Check offhand 1 tick and 3 ticks later (covers delay=0 and delay=1/2)
        plugin.getServer().getScheduler().runTaskLater(plugin,
                () -> checkOffhand(player, uuid), 1L);
        plugin.getServer().getScheduler().runTaskLater(plugin,
                () -> checkOffhand(player, uuid), 3L);
    }

    private void checkOffhand(Player player, UUID uuid) {
        if (!player.isOnline()) return;
        if (!isEnabled()) return;
        if (isExempt(player)) return;
        if (plugin.shouldSuppressFlags(player)) return;

        Long resMs = resurrectMs.get(uuid);
        if (resMs == null) return;

        long elapsed = System.currentTimeMillis() - resMs;

        ItemStack offhand = player.getInventory().getItemInOffHand();
        if (offhand.getType() != Material.TOTEM_OF_UNDYING) return;

        // They have a totem in offhand already — how fast was it?
        // Human minimum realistic re-equip: ~200 ms
        if (elapsed < 150) {
            int streak = fastSwapStreak.merge(uuid, 1, Integer::sum);
            if (streak >= 2) {
                flag(player, "totem_reequip elapsed=" + elapsed + "ms streak=" + streak);
                fastSwapStreak.put(uuid, 0);
            }
            resurrectMs.remove(uuid); // consumed, don't double-flag
        } else {
            fastSwapStreak.put(uuid, 0);
            resurrectMs.remove(uuid);
        }
    }
}
