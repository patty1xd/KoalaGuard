package com.koalaguard.check;

import com.koalaguard.KoalaGuard;
import com.koalaguard.data.PlayerData;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageByEntityEvent;

/**
 * Driven by the CombatProcessor when a player directly attacks an entity.
 * Rotation history (collected every move tick) is already available on
 * {@link PlayerData} so combat checks can analyse aim without their own
 * move listener.
 */
public abstract class CombatCheck extends Check {

    protected CombatCheck(KoalaGuard plugin, String name, String description) {
        super(plugin, name, CheckCategory.COMBAT, description);
    }

    public abstract void handle(PlayerData data, Player attacker, Entity victim,
                                EntityDamageByEntityEvent event);
}
