package com.koalaguard.checks.combat;

import com.koalaguard.KoalaGuard;
import com.koalaguard.check.CombatCheck;
import com.koalaguard.data.PlayerData;
import com.koalaguard.util.LocationUtil;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageByEntityEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * KillAura detection — combines several independent server-observable
 * signatures so a single weak heuristic never flags alone:
 *   A) Hitting a target far outside the crosshair (large look angle).
 *   B) Snap aim — a huge rotation in the tick(s) right before the hit.
 *   C) Multi-aura — landing hits on 3+ distinct entities in a moment.
 *   D) Hitting a target that is behind the player.
 * Evidence accumulates in a decaying buffer.
 */
public final class KillAuraCheck extends CombatCheck {

    public KillAuraCheck(KoalaGuard plugin) {
        super(plugin, "killaura", "Automated combat (multi-target / off-angle / snap aim)");
    }

    @Override
    @SuppressWarnings("unchecked")
    public void handle(PlayerData data, Player attacker, Entity victim, EntityDamageByEntityEvent e) {
        long now = System.currentTimeMillis();
        double angle = LocationUtil.horizontalAngle(attacker, victim);

        double evidence = 0;
        String why = null;

        // A/D — off-angle / behind
        if (angle > 95) { evidence += 3.0; why = "behind angle=" + (int) angle + "°"; }
        else if (angle > 70) { evidence += 1.6; why = "off-angle=" + (int) angle + "°"; }

        // B — snap: sum of the last two yaw deltas immediately preceding the hit
        float snap = 0;
        int taken = 0;
        java.util.Iterator<Float> it = data.yawSamples.descendingIterator();
        while (it.hasNext() && taken < 2) { snap += Math.abs(it.next()); taken++; }
        if (snap > 75 && angle < 35) { evidence += 2.2; why = "snap=" + (int) snap + "°"; }

        // C — multi-aura
        Map<UUID, Long> recent = data.obj(k("targets"));
        if (recent == null) { recent = new HashMap<>(); data.setObj(k("targets"), recent); }
        recent.values().removeIf(t -> now - t > 350);
        recent.put(victim.getUniqueId(), now);
        if (recent.size() >= 3) {
            evidence += 4.0;
            why = "multi-target=" + recent.size();
            recent.clear();
        }

        if (evidence > 0) {
            double buf = data.addBuffer(k("b"), evidence, 14.0);
            if (buf >= 7.0) {
                fail(data, attacker, why);
                data.setBuffer(k("b"), 2.0);
            }
        } else {
            data.subBuffer(k("b"), 1.0);
        }
    }
}
