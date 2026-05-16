package com.koalaguard.checks.combat;

import com.koalaguard.KoalaGuard;
import com.koalaguard.check.CombatCheck;
import com.koalaguard.data.PlayerData;
import com.koalaguard.util.LocationUtil;
import com.koalaguard.util.MathUtil;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageByEntityEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * Hitbox-expander detection.
 *
 * An inflated client-side hitbox lets the player land hits that are
 * consistently off-centre yet without the violent snap of KillAura. The
 * statistical signature: a high mean look-angle with suspiciously low
 * variance over a long sample (always clicking the same inflated edge).
 */
public final class HitboxCheck extends CombatCheck {

    private static final int SAMPLE = 20;

    public HitboxCheck(KoalaGuard plugin) {
        super(plugin, "hitbox", "Hitting through an enlarged hitbox");
    }

    @Override
    @SuppressWarnings("unchecked")
    public void handle(PlayerData data, Player attacker, Entity victim, EntityDamageByEntityEvent e) {
        // Snap belongs to KillAura — don't double count.
        if (data.deltaYaw > 30) return;

        double angle = LocationUtil.horizontalAngle(attacker, victim);
        List<Double> angles = data.obj(k("a"));
        if (angles == null) { angles = new ArrayList<>(); data.setObj(k("a"), angles); }
        angles.add(angle);
        if (angles.size() > SAMPLE) angles.remove(0);

        if (angles.size() == SAMPLE) {
            double mean = MathUtil.average(angles);
            double var = MathUtil.variance(angles);
            if (mean > 42 && mean < 120 && var < 240) {
                fail(data, attacker, String.format("wide-angle mean=%.1f° var=%.1f", mean, var));
                angles.clear();
            }
        }
    }
}
