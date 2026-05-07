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
        if (player.hasPermission("koalaguard.bypass")) return;

        UUID uuid = player.getUniqueId();
        long now = System.currentTimeMillis();
        long slotChange = lastSlotChange.getOrDefault(uuid, 0L);

        // If they switched to this weapon and hit within 1 tick = autoweapon
        if (now - slotChange < 50 && slotChange != 0) {
            flag(player, "hit_after_slot_change=" + (now - slotChange) + "ms");
        }
        lastHit.put(uuid, now);
    }
}
