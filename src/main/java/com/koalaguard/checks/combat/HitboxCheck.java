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
 * Hitbox expander — consistently landing hits off-centre (by the client's
 * real aim) without the violent snap of KillAura: high mean look-angle with
 * suspiciously low variance over a long sample.
 */
public final class HitboxCheck extends CombatCheck {

    private static final int SAMPLE = 20;

    public HitboxCheck(KoalaGuard plugin) {
        super(plugin, "hitbox", "Hitting through an enlarged hitbox");
    }

    @Override
    @SuppressWarnings("unchecked")
    public void handle(PlayerData d, Player attacker, Entity victim, EntityDamageByEntityEvent e) {
        if (d.deltaYaw > 30) return; // snap is KillAura's domain

        double ex, ey, ez;
        if (d.pHasPos) { ex = d.pX; ey = d.pY + attacker.getEyeHeight(); ez = d.pZ; }
        else { ex = attacker.getEyeLocation().getX(); ey = attacker.getEyeLocation().getY(); ez = attacker.getEyeLocation().getZ(); }
        double angle = LocationUtil.lookAngle(ex, ey, ez, d.pYaw, d.pPitch, victim);

        List<Double> a = d.obj(k("a"));
        if (a == null) { a = new ArrayList<>(); d.setObj(k("a"), a); }
        a.add(angle);
        if (a.size() > SAMPLE) a.remove(0);

        if (a.size() == SAMPLE) {
            double mean = MathUtil.average(a);
            double var = MathUtil.variance(a);
            if (mean > 40 && mean < 120 && var < 220) {
                fail(d, attacker, String.format("wide-angle mean=%.1f° var=%.1f", mean, var));
                a.clear();
            }
        }
    }
}
