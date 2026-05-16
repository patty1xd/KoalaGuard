package com.koalaguard.checks.combat;

import com.koalaguard.KoalaGuard;
import com.koalaguard.check.CombatCheck;
import com.koalaguard.data.PlayerData;
import com.koalaguard.util.MathUtil;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageByEntityEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * AimAssist / smooth-aimbot — analysed on the REAL packet rotation history.
 * A constant-step assist produces many non-zero yaw deltas with extremely
 * low variance and a measurable repeating granularity (GCD). A human mouse
 * never holds that.
 */
public final class AimAssistCheck extends CombatCheck {

    public AimAssistCheck(KoalaGuard plugin) {
        super(plugin, "aimassist", "Robotic constant-step aim");
    }

    @Override
    public void handle(PlayerData d, Player attacker, Entity victim, EntityDamageByEntityEvent e) {
        List<Float> all = d.yawDeltas(30);
        List<Float> nz = new ArrayList<>();
        for (Float f : all) if (f > 0.06f && f < 30f) nz.add(f);
        if (nz.size() < 16) return;

        double mean = MathUtil.average(nz);
        double var = MathUtil.variance(nz);

        // repeating granularity
        double g = nz.get(0);
        for (Float f : nz) g = MathUtil.gcd(g, f);

        if (mean > 0.25 && mean < 12 && var < 1.3 && g > 0.05 && g < 6) {
            double buf = d.addBuffer(k("b"), 3.0, 9.0);
            if (buf >= 6.0) {
                fail(d, attacker, String.format("constant-aim mean=%.2f var=%.3f gcd=%.3f", mean, var, g));
                d.setBuffer(k("b"), 1.0);
            }
        } else {
            d.subBuffer(k("b"), 1.0);
        }
    }
}
