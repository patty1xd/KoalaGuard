package com.koalaguard.checks.combat;

import com.koalaguard.KoalaGuard;
import com.koalaguard.check.CombatCheck;
import com.koalaguard.data.PlayerData;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageByEntityEvent;

/**
 * Impossible attack state — delivering a melee hit while the hand is raised
 * (eating/drinking/charging a bow or trident/blocking a shield). Uses the
 * packet item-use state (USE_ITEM → RELEASE_USE_ITEM) for precision.
 */
public final class InvalidAttackCheck extends CombatCheck {

    public InvalidAttackCheck(KoalaGuard plugin) {
        super(plugin, "invalidattack", "Attacking while using an item");
    }

    @Override
    public void handle(PlayerData d, Player attacker, Entity victim, EntityDamageByEntityEvent e) {
        boolean usingPacket = d.usingItem && System.currentTimeMillis() - d.useStartMs > 100;
        boolean usingBukkit;
        try { usingBukkit = attacker.isHandRaised() && attacker.getActiveItem() != null
                && !attacker.getActiveItem().getType().isAir(); }
        catch (Throwable t) { usingBukkit = false; }

        if (usingPacket || usingBukkit) {
            int s = d.incInt(k("s"));
            if (s >= 3) {
                fail(d, attacker, "attacked while using an item streak=" + s);
                d.setInt(k("s"), 0);
            }
        } else {
            d.setInt(k("s"), 0);
        }
    }
}
