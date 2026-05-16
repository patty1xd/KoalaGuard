package com.koalaguard.checks.combat;

import com.koalaguard.KoalaGuard;
import com.koalaguard.check.CombatCheck;
import com.koalaguard.data.PlayerData;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageByEntityEvent;

/**
 * NoSwing / no-hand-animation detection.
 *
 * A legitimate client always sends an arm-swing animation packet with (or
 * just before) an attack. Some KillAura/packet clients omit it. We flag only
 * on a sustained streak of swing-less hits to absorb rare event ordering.
 */
public final class NoSwingCheck extends CombatCheck {

    public NoSwingCheck(KoalaGuard plugin) {
        super(plugin, "noswing", "Attacking without sending the swing animation");
    }

    @Override
    public void handle(PlayerData data, Player attacker, Entity victim, EntityDamageByEntityEvent e) {
        long now = System.currentTimeMillis();
        boolean swung = data.swingsSinceAttack > 0 || (now - data.lastArmSwingMs) < 250;

        if (!swung) {
            int s = data.incInt(k("s"));
            if (s >= 4) {
                fail(data, attacker, "swing-less hits streak=" + s);
                data.setInt(k("s"), 0);
            }
        } else {
            data.setInt(k("s"), 0);
        }
    }
}
