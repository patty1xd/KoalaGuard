package com.koalaguard.checks.combat;

import com.koalaguard.KoalaGuard;
import com.koalaguard.check.CombatCheck;
import com.koalaguard.data.PlayerData;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.potion.PotionEffectType;

import java.util.ArrayList;
import java.util.List;

/**
 * Criticals detection.
 *
 * A packet-crit client spoofs a tiny fall so every hit is critical. Ground
 * state at the damage event is unreliable, so we analyse the crit RATE: a
 * legit player crits ~10-25% of hits (only when actually falling); a crit
 * client crits ~100%. Players genuinely airborne from a real jump are
 * exempt.
 */
public final class CriticalsCheck extends CombatCheck {

    private static final double CRIT_RATIO = 1.3;
    private static final int WINDOW = 12;

    public CriticalsCheck(KoalaGuard plugin) {
        super(plugin, "criticals", "Forcing critical hits via fake fall packets");
    }

    @Override
    @SuppressWarnings("unchecked")
    public void handle(PlayerData data, Player attacker, Entity victim, EntityDamageByEntityEvent e) {
        if (attacker.isInWater() || attacker.isInLava() || attacker.isClimbing()
                || attacker.isInsideVehicle() || attacker.isGliding()) return;
        // genuine jump arc — crits are legitimate
        if (!data.onGround && data.deltaY < -0.07 && attacker.getFallDistance() > 0.4) return;

        AttributeInstance ai = attacker.getAttribute(Attribute.ATTACK_DAMAGE);
        double base = ai != null ? ai.getValue() : 1.0;
        double strength = 0;
        if (attacker.hasPotionEffect(PotionEffectType.STRENGTH)) {
            int amp = attacker.getPotionEffect(PotionEffectType.STRENGTH).getAmplifier();
            strength = 3.0 * (amp + 1);
        }
        double weak = 1.0;
        if (attacker.hasPotionEffect(PotionEffectType.WEAKNESS)) {
            int amp = attacker.getPotionEffect(PotionEffectType.WEAKNESS).getAmplifier();
            weak = Math.pow(0.5, amp + 1);
        }
        double nonCritMax = (base + strength) * weak;
        if (nonCritMax <= 0) return;

        boolean crit = e.getDamage() >= nonCritMax * CRIT_RATIO;

        List<Boolean> hist = data.obj(k("h"));
        if (hist == null) { hist = new ArrayList<>(); data.setObj(k("h"), hist); }
        hist.add(crit);
        if (hist.size() > WINDOW) hist.remove(0);

        if (hist.size() == WINDOW) {
            long crits = hist.stream().filter(b -> b).count();
            double rate = (double) crits / WINDOW;
            if (rate >= 0.75) {
                double buf = data.addBuffer(k("b"), 3.0, 9.0);
                if (buf >= 6.0) {
                    fail(data, attacker, String.format("crit-rate=%.0f%% over %d hits",
                            rate * 100, WINDOW));
                    data.setBuffer(k("b"), 1.0);
                }
                hist.clear();
            } else {
                data.subBuffer(k("b"), 1.0);
            }
        }
    }
}
