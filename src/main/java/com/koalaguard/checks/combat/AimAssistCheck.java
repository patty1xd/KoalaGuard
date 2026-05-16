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
 * AimAssist / smooth-aimbot detection.
 *
 * AimAssist rotates the player toward the target by a near-constant step
 * each tick. A real mouse produces highly irregular rotation deltas; a
 * fixed-step assist produces many non-zero deltas with extremely low
 * variance. Evaluated on the rotation history captured every move tick.
 */
public final class AimAssistCheck extends CombatCheck {

    public AimAssistCheck(KoalaGuard plugin) {
        super(plugin, "aimassist", "Robotic constant-step aim correction");
    }

    @Override
    public void handle(PlayerData data, Player attacker, Entity victim, EntityDamageByEntityEvent e) {
        List<Float> nonZero = new ArrayList<>();
        for (Float y : data.yawSamples) if (y != null && y > 0.06f && y < 35f) nonZero.add(y);
        if (nonZero.size() < 16) return;

        double mean = MathUtil.average(nonZero);
        double var = MathUtil.variance(nonZero);
        int dupes = MathUtil.duplicates(scaled(nonZero));

        // Constant-rate turn: meaningful average movement, almost no variance.
        if (mean > 0.25 && mean < 14 && var < 1.4 && dupes >= 5) {
            double buf = data.addBuffer(k("b"), 3.0, 9.0);
            if (buf >= 6.0) {
                fail(data, attacker, String.format("constant-aim mean=%.2f° var=%.3f", mean, var));
                data.setBuffer(k("b"), 1.0);
            }
        } else {
            data.subBuffer(k("b"), 1.0);
        }
    }

    private List<Long> scaled(List<Float> in) {
        List<Long> out = new ArrayList<>(in.size());
        for (Float f : in) out.add(Math.round(f * 100.0));
        return out;
    }
}
