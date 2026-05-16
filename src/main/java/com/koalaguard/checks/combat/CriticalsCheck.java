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
 * Criticals — packet model.
 *
 * Vanilla only crits while genuinely airborne and descending. Packet-crit
 * clients spoof a micro fall so the hit crits while the client's own last
 * movement packet still says onGround / not-falling. We detect that direct
 * contradiction (a crit landed while the player was packet-grounded / not
 * descending, fully charged, no knockback) and also keep a crit-rate window
 * as a second, independent signal.
 */
public final class CriticalsCheck extends CombatCheck {

    private static final double CRIT_RATIO = 1.4;
    private static final int WINDOW = 14;

    public CriticalsCheck(KoalaGuard plugin) {
        super(plugin, "criticals", "Forcing critical hits via fake fall packets");
    }

    @Override
    @SuppressWarnings("unchecked")
    public void handle(PlayerData d, Player attacker, Entity victim, EntityDamageByEntityEvent e) {
        if (attacker.isInWater() || attacker.isInLava() || attacker.isClimbing()
                || attacker.isInsideVehicle() || attacker.isGliding()) return;
        if (attacker.hasPotionEffect(PotionEffectType.LEVITATION)
                || attacker.hasPotionEffect(PotionEffectType.SLOW_FALLING)) return;
        if (attacker.isRiptiding()) return;

        long now = System.currentTimeMillis();
        if (now - d.lastVelocityMs < 1000 || now - d.lastDamageMs < 800
                || now - d.lastTeleportMs < 1500 || now - d.slimeBounceMs < 1200) return;

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

        // ── crit-rate window (independent signal) ──
        List<Boolean> hist = d.obj(k("h"));
        if (hist == null) { hist = new ArrayList<>(); d.setObj(k("h"), hist); }
        hist.add(crit);
        if (hist.size() > WINDOW) hist.remove(0);

        if (!crit) { d.subBuffer(k("b"), 0.5); return; }

        // ── direct contradiction: crit while the client's own movement says
        //    grounded / not descending, on a fully-charged hit ──
        float cooldown;
        try { cooldown = attacker.getAttackCooldown(); } catch (Throwable t) { cooldown = 1f; }
        boolean charged = cooldown > 0.84f;
        boolean notFalling = d.pOnGround || d.serverGround || d.deltaY > -0.03;

        if (charged && notFalling) {
            double buf = d.addBuffer(k("b"), 3.0, 12.0);
            if (buf >= 6.0) {
                fail(d, attacker, String.format("crit while grounded (pGround=%b dY=%.3f cd=%.2f)",
                        d.pOnGround, d.deltaY, cooldown));
                d.setBuffer(k("b"), 1.0);
            }
        }

        if (hist.size() == WINDOW) {
            long crits = hist.stream().filter(b -> b).count();
            double rate = (double) crits / WINDOW;
            if (rate >= 0.85) {
                fail(d, attacker, String.format("crit-rate=%.0f%% over %d hits", rate * 100, WINDOW));
                hist.clear();
                d.setBuffer(k("b"), 1.0);
            }
        }
    }
}
