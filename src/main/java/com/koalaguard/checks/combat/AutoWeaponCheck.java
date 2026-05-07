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

public class AutoWeaponCheck extends Check {

    private final Map<UUID, Long> lastSlotChange = new HashMap<>();
    private final Map<UUID, Long> lastHit = new HashMap<>();

    public AutoWeaponCheck(KoalaGuard plugin) { super(plugin, "autoweapon"); }

    @EventHandler
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
        long now = System.currentTimeMillis();
        long slotChange = lastSlotChange.getOrDefault(uuid, 0L);

        // Conservative: only flag if hit is basically instant after slot change.
        if (slotChange != 0 && now - slotChange < 35) {
            flag(player, "hit_after_slot_change=" + (now - slotChange) + "ms");
        }
        lastHit.put(uuid, now);
    }
}
