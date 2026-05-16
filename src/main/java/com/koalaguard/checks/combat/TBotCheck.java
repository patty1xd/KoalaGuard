package com.koalaguard.checks.combat;

import com.koalaguard.KoalaGuard;
import com.koalaguard.check.CombatCheck;
import com.koalaguard.data.PlayerData;
import com.koalaguard.util.LocationUtil;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageByEntityEvent;

import java.util.List;

/**
 * TriggerBot — a sustained run of dead-centre hits (by real client aim) on a
 * MOVING entity while the client performs essentially no tracking rotation.
 * A human tracking a moving target always micro-corrects.
 */
public final class TBotCheck extends CombatCheck {

    public TBotCheck(KoalaGuard plugin) {
        super(plugin, "tbot", "Auto-attacking the instant a target is centred");
    }

    @Override
    public void handle(PlayerData d, Player attacker, Entity victim, EntityDamageByEntityEvent e) {
        if (!(victim instanceof LivingEntity le)) { d.setInt(k("s"), 0); return; }

        double ex, ey, ez;
        if (d.pHasPos) { ex = d.pX; ey = d.pY + attacker.getEyeHeight(); ez = d.pZ; }
        else { ex = attacker.getEyeLocation().getX(); ey = attacker.getEyeLocation().getY(); ez = attacker.getEyeLocation().getZ(); }

        double angle = LocationUtil.lookAngle(ex, ey, ez, d.pYaw, d.pPitch, victim);
        boolean victimMoving = le.getVelocity().lengthSquared() > 0.003;

        List<Float> yd = d.yawDeltas(3);
        double rot = 0; for (float f : yd) rot += f;

        if (victimMoving && angle < 4.0 && rot < 0.8) {
            int s = d.incInt(k("s"));
            if (s >= cfgI("min-streak", 6)) {
                fail(d, attacker, String.format("centred w/o tracking streak=%d angle=%.1f°", s, angle));
                d.setInt(k("s"), 0);
            }
        } else {
            d.addInt(k("s"), -1);
        }
    }
}
