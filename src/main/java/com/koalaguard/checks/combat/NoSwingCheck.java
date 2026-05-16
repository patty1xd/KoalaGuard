package com.koalaguard.checks.combat;

import com.koalaguard.KoalaGuard;
import com.koalaguard.check.CombatCheck;
import com.koalaguard.data.PlayerData;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageByEntityEvent;

/**
 * NoSwing — a legitimate client always sends an ARM swing animation packet
 * with (or microseconds before) the attack packet. Some KillAura/packet
 * clients omit it. We compare the true ANIMATION packet time to the true
 * INTERACT_ENTITY time; a sustained streak of swing-less hits flags.
 */
public final class NoSwingCheck extends CombatCheck {

    public NoSwingCheck(KoalaGuard plugin) {
        super(plugin, "noswing", "Attacking without sending the swing animation");
    }

    @Override
    public void handle(PlayerData d, Player attacker, Entity victim, EntityDamageByEntityEvent e) {
        long atk = d.lastAttackPacketMs;
        long swing = d.lastSwingMs;
        boolean swung = atk != 0 && swing != 0 && Math.abs(atk - swing) <= 120;
        // also accept a swing in the last 150ms (event-order tolerance)
        if (!swung && swing != 0 && System.currentTimeMillis() - swing < 150) swung = true;

        if (!swung) {
            int s = d.incInt(k("s"));
            if (s >= 4) {
                fail(d, attacker, "swing-less hits streak=" + s);
                d.setInt(k("s"), 0);
            }
        } else {
            d.setInt(k("s"), 0);
        }
    }
}
