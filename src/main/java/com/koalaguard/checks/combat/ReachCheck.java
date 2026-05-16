package com.koalaguard.checks.combat;

import com.koalaguard.KoalaGuard;
import com.koalaguard.check.CombatCheck;
import com.koalaguard.data.PlayerData;
import com.koalaguard.util.LocationUtil;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageByEntityEvent;

/**
 * Reach detection.
 *
 * Measures eye → nearest hitbox-edge distance (not centre, to avoid width
 * false positives) and compares against the vanilla 3.0 melee limit plus a
 * latency-scaled buffer. A decaying buffer requires several over-reach hits
 * before flagging, so legitimate edge hits during lag never punish.
 */
public final class ReachCheck extends CombatCheck {

    public ReachCheck(KoalaGuard plugin) {
        super(plugin, "reach", "Attacking from beyond melee range");
    }

    @Override
    public void handle(PlayerData data, Player attacker, Entity victim, EntityDamageByEntityEvent e) {
        double max = cfgD("max-reach", 3.0);
        int ping = plugin.getMetrics().pingMs(attacker);
        double pingBuffer = 0.20 + (ping > 0 ? Math.min(0.85, ping / 220.0) : 0.0);

        double dist = LocationUtil.hitboxDistance(attacker, victim);
        double limit = max + pingBuffer;

        if (dist > limit) {
            double over = dist - limit;
            double buf = data.addBuffer(k("b"), 1.0 + over * 4.0, 10.0);
            if (buf >= 4.0) {
                fail(data, attacker, String.format("dist=%.3f limit=%.2f ping=%dms",
                        dist, limit, ping));
                data.setBuffer(k("b"), 1.0);
            }
        } else {
            data.subBuffer(k("b"), 0.75);
        }
    }
}
