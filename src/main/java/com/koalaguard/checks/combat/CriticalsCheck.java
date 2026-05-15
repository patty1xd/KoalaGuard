package com.koalaguard.checks.combat;

import com.koalaguard.KoalaGuard;
import com.koalaguard.checks.Check;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.potion.PotionEffectType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Detects Meteor Criticals.
 *
 * From Meteor's source (Criticals.java):
 *   - "Packet" mode: sends two fake move packets before attacking:
 *       Y + 0.0625 (onGround=false), then Y + 0.0 (onGround=true).
 *     The server registers the attack while the player was briefly "falling",
 *     applying the 1.5× critical hit multiplier.
 *   - "UpdatedNCP" mode: Y + 0.0000008 — near-invisible spoof.
 *
 * Why the old ground-state check was unreliable:
 *   player.isOnGround() reflects the LAST packet received. By the time
 *   EntityDamageByEntityEvent fires, Meteor has already sent the second packet
 *   (Y+0.0, onGround=true), so isOnGround() often returns true regardless.
 *   Checking it alone produces inconsistent results.
 *
 * Better strategy — crit RATE analysis:
 *   A normal player only crits when jumping (~10–25% of hits).
 *   Meteor Criticals applies the multiplier on EVERY hit (~100%).
 *   Detection: track the fraction of hits where actual damage is ≥ 1.28×
 *   the computed non-crit maximum over a rolling window of 10 hits.
 *   Flag if crit rate > 70% over that window (requires streak of 2 windows).
 *
 * Damage model:
 *   nonCritMax = (ATTACK_DAMAGE attribute + strengthBonus) * weaknessMult
 *   The ATTACK_DAMAGE attribute already includes Sharpness and other
 *   weapon enchantment bonuses via their registered AttributeModifiers.
 *
 * False-positive guards:
 *   - Attack speed cooldown: if cooldown < 0.9 the damage is reduced, meaning
 *     a crit at partial cooldown won't exceed our threshold anyway.
 *   - Exempt players in water, lava, climbing, gliding, vehicle (legit crits impossible).
 *   - Exempt players actively falling from a real jump (getFallDistance() > 0.5
 *     AND velocity Y < -0.1 — genuinely airborne, not packet spoofing).
 */
public class CriticalsCheck extends Check {

    private static final double CRIT_RATIO      = 1.28; // 1.5× crit with some tolerance
    private static final double FLAG_CRIT_RATE  = 0.70; // 70% crit rate = suspicious
    private static final int    WINDOW_SIZE     = 10;

    private final Map<UUID, List<Boolean>> critHistory = new HashMap<>();
    private final Map<UUID, Integer>       flagStreak  = new HashMap<>();

    public CriticalsCheck(KoalaGuard plugin) { super(plugin, "criticals"); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        if (!isEnabled()) return;
        if (!(event.getDamager() instanceof Player player)) return;
        if (isExempt(player)) return;
        if (plugin.shouldSuppressFlags(player)) return;

        // Environments where crits are impossible (server rejects them)
        if (player.isInWater() || player.isInLava()) return;
        if (player.isOnClimbable()) return;
        if (player.isInsideVehicle()) return;
        if (player.isGliding()) return;

        // Player genuinely airborne from a real jump — crits are legitimate
        // (falling speed < -0.1 means they actually jumped, not packet spoofed)
        if (!player.isOnGround()
                && player.getFallDistance() > 0.5
                && player.getVelocity().getY() < -0.10) return;

        UUID uuid = player.getUniqueId();

        double base = getBaseDamage(player);

        // Strength: adds 3 × (amp + 1) to base damage
        double strength = 0;
        if (player.hasPotionEffect(PotionEffectType.STRENGTH)) {
            int amp = player.getPotionEffect(PotionEffectType.STRENGTH).getAmplifier();
            strength = 3.0 * (amp + 1);
        }

        // Weakness: multiplies by 0.5^(amp+1)
        double weakMult = 1.0;
        if (player.hasPotionEffect(PotionEffectType.WEAKNESS)) {
            int amp = player.getPotionEffect(PotionEffectType.WEAKNESS).getAmplifier();
            weakMult = Math.pow(0.5, amp + 1);
        }

        double nonCritMax = (base + strength) * weakMult;
        if (nonCritMax <= 0) return;

        double actual    = event.getDamage();
        boolean wasCrit  = actual >= nonCritMax * CRIT_RATIO;

        List<Boolean> history = critHistory.computeIfAbsent(uuid, k -> new ArrayList<>());
        history.add(wasCrit);
        if (history.size() > WINDOW_SIZE) history.remove(0);

        if (history.size() == WINDOW_SIZE) {
            long crits = history.stream().filter(b -> b).count();
            double rate = (double) crits / WINDOW_SIZE;

            if (rate >= FLAG_CRIT_RATE) {
                int s = flagStreak.merge(uuid, 1, Integer::sum);
                if (s >= 2) {
                    flag(player, String.format("crit_rate=%.0f%% over_%d_hits streak=%d",
                            rate * 100, WINDOW_SIZE, s));
                    flagStreak.put(uuid, 0);
                    history.clear();
                }
            } else {
                flagStreak.put(uuid, 0);
            }
        }
    }

    private static double getBaseDamage(Player player) {
        AttributeInstance attr = player.getAttribute(Attribute.ATTACK_DAMAGE);
        return attr != null ? attr.getValue() : 1.0;
    }
}
