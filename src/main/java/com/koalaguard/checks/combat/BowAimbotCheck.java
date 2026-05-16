package com.koalaguard.checks.combat;

import com.koalaguard.KoalaGuard;
import com.koalaguard.check.CheckCategory;
import com.koalaguard.check.ListenerCheck;
import com.koalaguard.data.PlayerData;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.entity.EntityDamageByEntityEvent;

/**
 * BowAimbot detection.
 *
 * Aimbot snaps the required projectile pitch and then holds perfectly still.
 * If a player repeatedly lands arrows on a moving target with almost no
 * rotation in the moments before the shot, that static-aim accuracy is not
 * humanly reproducible.
 */
public final class BowAimbotCheck extends ListenerCheck {

    public BowAimbotCheck(KoalaGuard plugin) {
        super(plugin, "bowaimbot", CheckCategory.COMBAT, "Bow aimbot (static aim, inhuman accuracy)");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHit(EntityDamageByEntityEvent event) {
        if (!isEnabled()) return;
        if (!(event.getDamager() instanceof AbstractArrow arrow)) return;
        if (!(arrow.getShooter() instanceof Player player)) return;
        if (!(event.getEntity() instanceof LivingEntity target)) return;
        if (isExempt(player)) return;
        PlayerData data = plugin.getDataManager().get(player);
        if (data == null) return;

        double dist = player.getLocation().distance(target.getLocation());
        if (dist > 24 || dist < 4) return;
        boolean moving = target.getVelocity().lengthSquared() > 0.004;
        if (!moving) { data.setInt(k("s"), 0); return; }

        float rot = 0;
        int n = 0;
        java.util.Iterator<Float> y = data.yawSamples.descendingIterator();
        java.util.Iterator<Float> p = data.pitchSamples.descendingIterator();
        while (y.hasNext() && n < 3) { rot += Math.abs(y.next()); n++; }
        n = 0;
        while (p.hasNext() && n < 3) { rot += Math.abs(p.next()); n++; }

        if (rot < 1.2) {
            int s = data.incInt(k("s"));
            if (s >= 4) {
                fail(data, player, String.format("static bow aim hits=%d dist=%.1f", s, dist));
                data.setInt(k("s"), 0);
            }
        } else {
            data.setInt(k("s"), 0);
        }
    }
}
