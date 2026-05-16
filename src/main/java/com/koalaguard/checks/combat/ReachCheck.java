package com.koalaguard.checks.combat;

import com.koalaguard.KoalaGuard;
import com.koalaguard.check.CombatCheck;
import com.koalaguard.data.PlayerData;
import com.koalaguard.util.LocationUtil;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageByEntityEvent;

/**
 * Reach — measured from the EXACT eye position the client reported in the
 * same packet flow as the attack (no Bukkit post-move desync), to the
 * nearest point of the victim's AABB. A decaying buffer + latency margin
 * keeps it false-positive-free while staying tight (~3.0+).
 */
public final class ReachCheck extends CombatCheck {

    public ReachCheck(KoalaGuard plugin) {
        super(plugin, "reach", "Attacking from beyond melee range");
    }

    @Override
    public void handle(PlayerData d, Player attacker, Entity victim, EntityDamageByEntityEvent e) {
        double ex, ey, ez;
        if (d.pHasPos) { ex = d.pX; ey = d.pY + attacker.getEyeHeight(); ez = d.pZ; }
        else { ex = attacker.getEyeLocation().getX(); ey = attacker.getEyeLocation().getY(); ez = attacker.getEyeLocation().getZ(); }

        double dist = LocationUtil.reachDistance(ex, ey, ez, victim);
        int ping = plugin.getMetrics().pingMs(attacker);
        double limit = cfgD("max-reach", 3.0) + 0.10 + (ping > 0 ? Math.min(0.7, ping / 300.0) : 0);

        if (dist > limit) {
            double buf = d.addBuffer(k("b"), 1.0 + (dist - limit) * 3.0, 10.0);
            if (buf >= 4.0) {
                fail(d, attacker, String.format("dist=%.3f limit=%.2f ping=%dms", dist, limit, ping));
                d.setBuffer(k("b"), 1.0);
            }
        } else {
            d.subBuffer(k("b"), 0.75);
        }
    }
}
