package com.koalaguard.processor;

import com.koalaguard.KoalaGuard;
import com.koalaguard.check.CombatCheck;
import com.koalaguard.data.PlayerData;
import org.bukkit.GameMode;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;

/**
 * Main-thread combat trigger. Fires on a real melee hit and runs the combat
 * checks, which now read the packet-accurate rotation/attack/swing model
 * captured by {@link PacketProcessor} (true client aim, true click cadence,
 * true multi-aura) instead of guessing from Bukkit state.
 */
public final class CombatProcessor implements Listener {

    private final KoalaGuard plugin;
    private static final int MAX = 40;

    public CombatProcessor(KoalaGuard plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onAttack(EntityDamageByEntityEvent event) {
        if (event.getCause() != EntityDamageEvent.DamageCause.ENTITY_ATTACK) return;
        if (!(event.getDamager() instanceof Player attacker)) return;
        Entity victim = event.getEntity();

        PlayerData d = plugin.getDataManager().get(attacker);
        if (d == null) return;
        if (attacker.hasPermission("koalaguard.bypass")
                || attacker.getGameMode() == GameMode.CREATIVE
                || attacker.getGameMode() == GameMode.SPECTATOR) return;

        long now = System.currentTimeMillis();
        long interval = d.lastAttackMs == 0 ? 0 : now - d.lastAttackMs;
        d.attackTimes.addLast(now);
        while (d.attackTimes.size() > MAX) d.attackTimes.removeFirst();
        if (interval > 0 && interval < 5000) {
            d.attackIntervals.addLast(interval);
            while (d.attackIntervals.size() > MAX) d.attackIntervals.removeFirst();
        }

        for (CombatCheck check : plugin.getCheckManager().combat()) {
            try {
                if (check.isEnabled()) check.handle(d, attacker, victim, event);
            } catch (Throwable t) {
                plugin.getLogger().warning("Check " + check.getName() + " error: " + t);
            }
        }

        d.lastAttackMs = now;
        d.lastAttackTarget = victim.getUniqueId();
        d.swingsSinceAttack = 0;
    }
}
