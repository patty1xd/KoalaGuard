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
 * AutoClicker detection.
 *
 * Two independent signals: a raw CPS ceiling no human sustains, and a
 * machine-regularity signal (extremely low click-interval variance and many
 * near-identical intervals) which catches "humanised" clickers that stay
 * under the CPS cap.
 */
public final class AutoClickerCheck extends CombatCheck {

    public AutoClickerCheck(KoalaGuard plugin) {
        super(plugin, "autoclicker", "Inhuman click rate / machine-regular clicking");
    }

    @Override
    public void handle(PlayerData data, Player attacker, Entity victim, EntityDamageByEntityEvent e) {
        long now = System.currentTimeMillis();
        long cps = data.attackTimes.stream().filter(t -> now - t <= 1000).count();
        int maxCps = cfgI("max-cps", 22);

        if (cps > maxCps) {
            double buf = data.addBuffer(k("b"), 2.0, 8.0);
            if (buf >= 4.0) {
                fail(data, attacker, "cps=" + cps + " max=" + maxCps);
                data.setBuffer(k("b"), 1.0);
            }
            return;
        }

        if (data.attackIntervals.size() >= 18 && cps >= 8) {
            List<Long> iv = new ArrayList<>(data.attackIntervals);
            double var = MathUtil.variance(iv);
            int dupes = MathUtil.duplicates(iv);
            double mean = MathUtil.average(iv);
            // Humans never hold variance < ~110 ms² with this many duplicates.
            if (var < 110 && dupes >= 6 && mean > 30) {
                fail(data, attacker, String.format("machine cps=%d var=%.1f dupes=%d mean=%.0fms",
                        cps, var, dupes, mean));
                data.attackIntervals.clear();
            }
        }
    }
}
