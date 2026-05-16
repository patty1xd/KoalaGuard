package com.koalaguard.checks.player;

import com.koalaguard.KoalaGuard;
import com.koalaguard.check.CheckCategory;
import com.koalaguard.check.ListenerCheck;
import com.koalaguard.data.PlayerData;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.entity.EntityRegainHealthEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

/**
 * Fast-regen detection — natural/SATIATED health regain ticks arriving
 * faster than the vanilla interval for the player's current Regeneration
 * level. Instant Health and food/magic regains are excluded.
 */
public final class RegenCheck extends ListenerCheck {

    public RegenCheck(KoalaGuard plugin) {
        super(plugin, "regen", CheckCategory.PLAYER, "Healing faster than vanilla");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onRegen(EntityRegainHealthEvent event) {
        if (!isEnabled()) return;
        if (!(event.getEntity() instanceof Player player)) return;
        if (isExempt(player)) return;
        EntityRegainHealthEvent.RegainReason r = event.getRegainReason();
        if (r != EntityRegainHealthEvent.RegainReason.SATIATED
                && r != EntityRegainHealthEvent.RegainReason.REGEN) return;

        PlayerData data = plugin.getDataManager().get(player);
        if (data == null) return;

        long now = System.currentTimeMillis();
        long last = data.getLong(k("last"));
        data.setLong(k("last"), now);
        if (last == 0) return;

        long min = 4000;
        PotionEffect reg = player.getPotionEffect(PotionEffectType.REGENERATION);
        if (reg != null) {
            int amp = reg.getAmplifier();
            min = amp >= 2 ? 500 : amp == 1 ? 1150 : 2300;
        }
        long threshold = (long) (min * 0.75);

        if (now - last < threshold) {
            int s = data.incInt(k("s"));
            if (s >= 3) {
                fail(data, player, "regen gap=" + (now - last) + "ms min=" + min + "ms streak=" + s);
                data.setInt(k("s"), 0);
            }
        } else {
            data.setInt(k("s"), 0);
        }
    }
}
