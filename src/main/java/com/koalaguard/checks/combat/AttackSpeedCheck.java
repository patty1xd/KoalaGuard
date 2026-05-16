package com.koalaguard.checks.combat;

import com.koalaguard.KoalaGuard;
import com.koalaguard.check.CombatCheck;
import com.koalaguard.data.PlayerData;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageByEntityEvent;

/**
 * Attack-speed / no-cooldown detection (1.9+ combat).
 *
 * Each landed melee hit is sampled for the attacker's weapon cooldown
 * (1.0 = fully charged). A human's hits average a healthy charge; clients
 * that ignore the cooldown land a sustained stream of near-uncharged hits.
 * We track the rolling fraction of uncharged hits over a long window.
 */
public final class AttackSpeedCheck extends CombatCheck {

    public AttackSpeedCheck(KoalaGuard plugin) {
        super(plugin, "attackspeed", "Attacking faster than the weapon cooldown permits");
    }

    @Override
    public void handle(PlayerData data, Player attacker, Entity victim, EntityDamageByEntityEvent e) {
        float cooldown;
        try { cooldown = attacker.getAttackCooldown(); }
        catch (Throwable t) { return; }

        double low = data.buffer(k("low"));
        int total = data.incInt(k("n"));

        if (cooldown < 0.55f) low = data.addBuffer(k("low"), 1.0, 1_000_000);
        else data.subBuffer(k("low"), 0.6);
        low = data.buffer(k("low"));

        if (total >= 24) {
            double ratio = low / total;
            if (ratio > 0.85) {
                fail(data, attacker, String.format("uncharged-hit ratio=%.0f%% over %d hits",
                        ratio * 100, total));
            }
            data.setInt(k("n"), 0);
            data.setBuffer(k("low"), 0);
        }
    }
}
