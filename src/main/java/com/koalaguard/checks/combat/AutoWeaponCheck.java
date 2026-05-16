package com.koalaguard.checks.combat;

import com.koalaguard.KoalaGuard;
import com.koalaguard.check.CheckCategory;
import com.koalaguard.check.ListenerCheck;
import com.koalaguard.data.PlayerData;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;

/**
 * AutoWeapon — packet model. Compares the TRUE held-slot-change packet time
 * to the TRUE attack packet time (both captured on the netty thread). A
 * human cannot scroll/select then attack within one tick.
 */
public final class AutoWeaponCheck extends ListenerCheck {

    public AutoWeaponCheck(KoalaGuard plugin) {
        super(plugin, "autoweapon", CheckCategory.COMBAT, "Switching weapon and hitting in one tick");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onAttack(EntityDamageByEntityEvent event) {
        if (!isEnabled()) return;
        if (event.getCause() != EntityDamageEvent.DamageCause.ENTITY_ATTACK) return;
        if (!(event.getDamager() instanceof Player player)) return;
        if (isExempt(player)) return;
        PlayerData d = plugin.getDataManager().get(player);
        if (d == null) return;

        long slot = d.lastSlotChangeMs;
        long atk = d.lastAttackPacketMs;
        if (slot == 0 || atk == 0) return;

        long gap = atk - slot;
        if (gap >= 0 && gap < 55) {
            int s = d.incInt(k("s"));
            if (s >= 3) {
                fail(d, player, "switch→hit " + gap + "ms streak=" + s);
                d.setInt(k("s"), 0);
            }
        } else {
            d.setInt(k("s"), 0);
        }
    }
}
