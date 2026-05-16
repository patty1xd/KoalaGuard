package com.koalaguard.checks.combat;

import com.koalaguard.KoalaGuard;
import com.koalaguard.check.CombatCheck;
import com.koalaguard.data.PlayerData;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageByEntityEvent;

/**
 * Impossible attack states.
 *
 * You cannot deliver a melee attack while your hand is raised (eating,
 * drinking, drawing a bow/trident, blocking with a shield) or while
 * sneaking-blocking. Clients that auto-attack ignore this restriction.
 */
public final class InvalidAttackCheck extends CombatCheck {

    public InvalidAttackCheck(KoalaGuard plugin) {
        super(plugin, "invalidattack", "Attacking while in a state that forbids attacking");
    }

    @Override
    public void handle(PlayerData data, Player attacker, Entity victim, EntityDamageByEntityEvent e) {
        boolean raised;
        try { raised = attacker.isHandRaised() && attacker.getActiveItem() != null
                && !attacker.getActiveItem().getType().isAir(); }
        catch (Throwable t) { return; }

        if (raised) {
            int s = data.incInt(k("s"));
            if (s >= 3) {
                fail(data, attacker, "attacked while using "
                        + attacker.getActiveItem().getType());
                data.setInt(k("s"), 0);
            }
        } else {
            data.setInt(k("s"), 0);
        }
    }
}
