package com.koalaguard.checks.combat;

import com.koalaguard.KoalaGuard;
import com.koalaguard.check.CombatCheck;
import com.koalaguard.data.PlayerData;
import com.koalaguard.util.LocationUtil;
import com.koalaguard.util.MathUtil;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageByEntityEvent;

import java.util.List;

/**
 * KillAura — fuses several packet-level signatures so no single heuristic
 * flags alone (research: MX/Shadow/Grim combat model):
 *   A) Hit while the target sits well outside the client's real look vector.
 *   B) Multi-aura — 3+ distinct entities attacked within ≤400 ms.
 *   C) Attack delivered while the hand is raised (eating/blocking/bow).
 *   D) Silent-aim — meaningful hits with ~0 rotation while the target moves.
 * Evidence feeds a decaying buffer; sustained evidence flags.
 */
public final class KillAuraCheck extends CombatCheck {

    public KillAuraCheck(KoalaGuard plugin) {
        super(plugin, "killaura", "Automated combat (off-angle / multi-aura / silent aim)");
    }

    @Override
    public void handle(PlayerData d, Player attacker, Entity victim, EntityDamageByEntityEvent e) {
        double ex, ey, ez;
        if (d.pHasPos) { ex = d.pX; ey = d.pY + attacker.getEyeHeight(); ez = d.pZ; }
        else { ex = attacker.getEyeLocation().getX(); ey = attacker.getEyeLocation().getY(); ez = attacker.getEyeLocation().getZ(); }

        double angle = LocationUtil.lookAngle(ex, ey, ez, d.pYaw, d.pPitch, victim);
        double evidence = 0;
        String why = null;

        if (angle > 90)      { evidence += 4.0; why = "behind " + (int) angle + "°"; }
        else if (angle > 65) { evidence += 2.0; why = "off-angle " + (int) angle + "°"; }

        int targets = d.distinctRecentTargets();
        if (targets >= 3) { evidence += 4.0; why = "multi-aura=" + targets; }

        if (d.usingItem && System.currentTimeMillis() - d.useStartMs > 120) {
            evidence += 3.0; why = "hit while using item";
        }

        // Silent aim: real hit on a MOVING target with essentially no rotation.
        List<Float> yd = d.yawDeltas(6);
        if (yd.size() >= 4) {
            double sum = 0; for (float f : yd) sum += f;
            boolean victimMoving = victim instanceof LivingEntity le
                    && le.getVelocity().lengthSquared() > 0.004;
            if (sum < 0.6 && victimMoving && angle < 8) { evidence += 2.5; why = "silent-aim"; }
        }

        if (evidence > 0) {
            double buf = d.addBuffer(k("b"), evidence, 16.0);
            if (buf >= 8.0) {
                fail(d, attacker, why);
                d.setBuffer(k("b"), 2.0);
            }
        } else {
            d.subBuffer(k("b"), 1.0);
        }
    }
}
