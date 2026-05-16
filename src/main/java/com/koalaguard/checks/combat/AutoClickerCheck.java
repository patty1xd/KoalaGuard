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
 * AutoClicker — driven by the TRUE attack packets (INTERACT_ENTITY) captured
 * on the netty thread, not by damage events. Two signals: a CPS ceiling no
 * human sustains, and machine regularity (very low click-interval variance
 * with many near-identical intervals) for "humanised" clickers under the cap.
 */
public final class AutoClickerCheck extends CombatCheck {

    public AutoClickerCheck(KoalaGuard plugin) {
        super(plugin, "autoclicker", "Inhuman / machine-regular click rate");
    }

    @Override
    public void handle(PlayerData d, Player attacker, Entity victim, EntityDamageByEntityEvent e) {
        long now = System.currentTimeMillis();
        long cps = d.attackPacketTimes.stream().filter(t -> now - t <= 1000).count();
        int maxCps = cfgI("max-cps", 22);

        if (cps > maxCps) {
            double buf = d.addBuffer(k("b"), 2.0, 8.0);
            if (buf >= 4.0) {
                fail(d, attacker, "cps=" + cps + " max=" + maxCps);
                d.setBuffer(k("b"), 1.0);
            }
            return;
        }

        List<Long> times = new ArrayList<>(d.attackPacketTimes);
        if (times.size() >= 20 && cps >= 8) {
            List<Long> iv = new ArrayList<>();
            for (int i = 1; i < times.size(); i++) iv.add(times.get(i) - times.get(i - 1));
            double var = MathUtil.variance(iv);
            int dupes = MathUtil.duplicates(iv);
            double mean = MathUtil.average(iv);
            double entropy = MathUtil.entropy(iv);   // human clicking is high-entropy
            double kurt = MathUtil.kurtosis(iv);      // bots over-peak or perfectly flat

            // machine: regular intervals (low var/entropy + dupes) OR a
            // pathological distribution shape (extreme kurtosis) at real CPS.
            boolean regular = var < 110 && dupes >= 7 && mean > 25;
            boolean lowEntropy = entropy < 1.4 && mean > 25;
            boolean shaped = Math.abs(kurt) > 6.0 && var < 400;
            if (regular || lowEntropy || shaped) {
                fail(d, attacker, String.format("machine cps=%d var=%.0f ent=%.2f kurt=%.1f",
                        cps, var, entropy, kurt));
                d.attackPacketTimes.clear();
            }
        }
    }
}
