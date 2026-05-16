package com.koalaguard.checks.combat;

import com.koalaguard.KoalaGuard;
import com.koalaguard.check.CombatCheck;
import com.koalaguard.data.PlayerData;
import com.koalaguard.util.LocationUtil;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageByEntityEvent;

/**
 * TriggerBot detection.
 *
 * A trigger bot fires the instant the crosshair crosses an enemy with no
 * tracking input of its own. Signature: a sustained run of dead-centre hits
 * (angle ≈ 0) on a moving entity while the attacker performs essentially no
 * rotation — a human tracking a moving target always micro-corrects.
 */
public final class TBotCheck extends CombatCheck {

    public TBotCheck(KoalaGuard plugin) {
        super(plugin, "tbot", "Auto-attacking the instant a target is centred");
    }

    @Override
    public void handle(PlayerData data, Player attacker, Entity victim, EntityDamageByEntityEvent e) {
        if (!(victim instanceof LivingEntity le)) { data.setInt(k("s"), 0); return; }
        boolean victimMoving = le.getVelocity().lengthSquared() > 0.003;
        double angle = LocationUtil.horizontalAngle(attacker, victim);

        float rot = 0;
        int n = 0;
        java.util.Iterator<Float> it = data.yawSamples.descendingIterator();
        while (it.hasNext() && n < 3) { rot += Math.abs(it.next()); n++; }

        boolean deadCentre = angle < 4.0;
        boolean noTracking = rot < 0.8;

        if (victimMoving && deadCentre && noTracking) {
            int s = data.incInt(k("s"));
            if (s >= cfgI("min-streak", 6)) {
                fail(data, attacker, String.format("centred hits w/o tracking streak=%d angle=%.1f°",
                        s, angle));
                data.setInt(k("s"), 0);
            }
        } else {
            data.addInt(k("s"), -1);
        }
    }
}
