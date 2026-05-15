package com.koalaguard.checks.combat;

import com.koalaguard.KoalaGuard;
import com.koalaguard.checks.Check;
import org.bukkit.Location;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.util.Vector;

import java.util.*;

/**
 * Detects TBot (TriggerBot / Auto-Hit).
 *
 * How it works:
 *   The moment an enemy player enters the attacker's hit range, the cheat
 *   automatically fires an attack packet — no manual click required.
 *   This is distinct from KillAura (which rotates to seek targets). TBot
 *   doesn't rotate; it just fires the instant the target crosses the reach
 *   threshold.
 *
 * Signature the server sees:
 *   1. The victim was OUTSIDE the attacker's reach just before the hit (they
 *      were approaching), and the attack fires the instant they step inside.
 *   2. The time between "victim crosses reach threshold" and "hit registered"
 *      is sub-human (< 1 tick / 50 ms). Humans need at least 100–200 ms
 *      to perceive a target entering range and click.
 *   3. This pattern is consistent across multiple engagements.
 *
 * Detection:
 *   - We snapshot each player's position on every move event.
 *   - On a damage event, we compare:
 *       a) victim's PREVIOUS position → distance to attacker (was outside reach?)
 *       b) victim's CURRENT position  → distance to attacker (now inside reach?)
 *   - If prev > reach + buffer AND current <= reach → "entry-hit" event.
 *   - We also check whether the victim was actively approaching the attacker.
 *   - Three consecutive entry-hits flag as TBot.
 */
public class TBotCheck extends Check {

    // Last known position of every online player (updated per move)
    private final Map<UUID, Location> prevLocation = new HashMap<>();

    // Per attacker: how many consecutive "entry-hit" events
    private final Map<UUID, Integer> entryHitStreak = new HashMap<>();

    // Per attacker: timestamp of last entry-hit (to reset stale streaks)
    private final Map<UUID, Long> lastEntryHitMs = new HashMap<>();

    public TBotCheck(KoalaGuard plugin) { super(plugin, "tbot"); }

    // Keep previous-position snapshots up to date for every player
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (event.getTo() == null) return;
        // Only record when they actually change position (not just head turn)
        if (event.getFrom().getX() == event.getTo().getX()
                && event.getFrom().getY() == event.getTo().getY()
                && event.getFrom().getZ() == event.getTo().getZ()) return;
        prevLocation.put(event.getPlayer().getUniqueId(), event.getFrom().clone());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        if (!isEnabled()) return;
        if (!(event.getDamager() instanceof Player attacker)) return;
        if (!(event.getEntity() instanceof LivingEntity victim)) return;
        if (isExempt(attacker)) return;
        if (plugin.shouldSuppressFlags(attacker)) return;

        UUID attackerUuid = attacker.getUniqueId();
        UUID victimUuid   = victim.getUniqueId();
        long now          = System.currentTimeMillis();

        // Reset streak if too much time passed between hits (different engagement)
        long lastEntry = lastEntryHitMs.getOrDefault(attackerUuid, 0L);
        if (now - lastEntry > 3000) {
            entryHitStreak.put(attackerUuid, 0);
        }

        // Ping-compensated reach threshold
        double maxReach = plugin.getConfig().getDouble("checks.reach.max-reach", 3.2);
        int ping = plugin.getMetrics().pingMs(attacker);
        double pingBuffer = 0.35 + (ping > 0 ? Math.min(0.7, ping / 200.0) : 0);
        double reachThreshold = maxReach + pingBuffer;

        // Current eye-to-hitbox distance
        double currentDist = attacker.getEyeLocation().distance(
                victim.getLocation().add(0, victim.getHeight() / 2.0, 0));

        // Previous position of the VICTIM (where they were before they moved)
        Location victimPrevLoc = prevLocation.get(victimUuid);
        if (victimPrevLoc == null) return; // no previous data yet

        double prevDist = attacker.getEyeLocation().distance(
                victimPrevLoc.clone().add(0, victim.getHeight() / 2.0, 0));

        // Was victim outside reach before, inside reach now?
        boolean crossedThreshold = prevDist > reachThreshold + 0.3 && currentDist <= reachThreshold;
        if (!crossedThreshold) {
            // Not an entry-hit. Decay the streak slightly.
            entryHitStreak.merge(attackerUuid, -1, (a, b) -> Math.max(0, a + b));
            return;
        }

        // Was the victim actively approaching the attacker?
        Vector victimVelocity   = victim.getVelocity();
        Vector toAttacker       = attacker.getLocation().toVector()
                                         .subtract(victim.getLocation().toVector());
        if (toAttacker.lengthSquared() > 0) toAttacker.normalize();
        double approachDot = victimVelocity.dot(toAttacker);
        // approachDot > 0 means victim is moving toward attacker
        if (approachDot < 0.05) return; // victim wasn't approaching — not a trigger scenario

        // Confirmed entry-hit: victim just walked into range, got hit immediately
        int streak = entryHitStreak.merge(attackerUuid, 1, Integer::sum);
        lastEntryHitMs.put(attackerUuid, now);

        int required = plugin.getConfig().getInt("checks.tbot.min-streak", 3);
        if (streak >= required) {
            flag(attacker, String.format(
                    "tbot streak=%d prevDist=%.2f curDist=%.2f reach=%.2f",
                    streak, prevDist, currentDist, reachThreshold));
            entryHitStreak.put(attackerUuid, 0);
        }
    }
}
