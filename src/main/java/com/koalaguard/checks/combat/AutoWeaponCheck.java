package com.koalaguard.checks.combat;

import com.koalaguard.KoalaGuard;
import com.koalaguard.checks.Check;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Detects Meteor AutoWeapon.
 *
 * From Meteor's source (AutoWeapon.java):
 *   - On each tick, scans hotbar for the highest-damage weapon.
 *   - If a better weapon is found, calls InvUtils.swap() — changes hotbar slot.
 *   - The swap fires PlayerItemHeldEvent, and Meteor's KillAura/AutoClicker hits
 *     immediately after in the SAME tick.
 *   - Server-side: slot change + EntityDamageByEntityEvent within < 50 ms.
 *
 * Human baseline: after manually switching weapon with scroll/number keys
 *   a player cannot attack in < 100 ms (typical is 200–400 ms).
 *
 * Detection:
 *   If a hit occurs within INSTANT_HIT_MS of a hotbar slot change, increment streak.
 *   Require 3 consecutive such events before flagging (absorbs accidental fast hits).
 */
public class AutoWeaponCheck extends Check {

    private final Map<UUID, Long>    lastSlotChange = new HashMap<>();
    private final Map<UUID, Integer> streak         = new HashMap<>();

    private static final long INSTANT_HIT_MS = 50L;

    public AutoWeaponCheck(KoalaGuard plugin) { super(plugin, "autoweapon"); }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onSlotChange(PlayerItemHeldEvent event) {
        lastSlotChange.put(event.getPlayer().getUniqueId(), System.currentTimeMillis());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        if (!isEnabled()) return;
        if (!(event.getDamager() instanceof Player player)) return;
        if (isExempt(player)) return;
        if (plugin.shouldSuppressFlags(player)) return;

        UUID uuid = player.getUniqueId();
        long now  = System.currentTimeMillis();
        Long slotMs = lastSlotChange.get(uuid);

        if (slotMs != null && now - slotMs < INSTANT_HIT_MS) {
            int s = streak.merge(uuid, 1, Integer::sum);
            if (s >= 3) {
                flag(player, "swap_to_hit=" + (now - slotMs) + "ms streak=" + s);
                streak.put(uuid, 0);
            }
        } else {
            streak.put(uuid, 0);
        }
    }
}
