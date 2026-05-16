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
import org.bukkit.event.player.PlayerItemHeldEvent;

/**
 * AutoWeapon detection — a hotbar slot change followed by a melee hit in the
 * same tick (≤ ~55 ms). A human cannot switch and attack that fast; the
 * cheat scans for the best weapon and hits in one tick.
 */
public final class AutoWeaponCheck extends ListenerCheck {

    public AutoWeaponCheck(KoalaGuard plugin) {
        super(plugin, "autoweapon", CheckCategory.COMBAT, "Switching weapon and hitting in one tick");
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onSlot(PlayerItemHeldEvent event) {
        PlayerData d = plugin.getDataManager().get(event.getPlayer());
        if (d != null) d.setLong(k("slot"), System.currentTimeMillis());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onAttack(EntityDamageByEntityEvent event) {
        if (!isEnabled()) return;
        if (event.getCause() != EntityDamageEvent.DamageCause.ENTITY_ATTACK) return;
        if (!(event.getDamager() instanceof Player player)) return;
        if (isExempt(player)) return;
        PlayerData data = plugin.getDataManager().get(player);
        if (data == null) return;

        long slotMs = data.getLong(k("slot"));
        if (slotMs == 0) return;
        long gap = System.currentTimeMillis() - slotMs;
        if (gap < 55) {
            int s = data.incInt(k("s"));
            if (s >= 3) {
                fail(data, player, "switch→hit " + gap + "ms streak=" + s);
                data.setInt(k("s"), 0);
            }
        } else {
            data.setInt(k("s"), 0);
        }
    }
}
