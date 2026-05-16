package com.koalaguard.processor;

import com.koalaguard.KoalaGuard;
import com.koalaguard.check.CombatCheck;
import com.koalaguard.data.PlayerData;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerAnimationEvent;
import org.bukkit.event.player.PlayerAnimationType;

/**
 * Single combat pipeline: maintains the shared attack model (intervals,
 * arm-swing correlation) and drives every melee combat check. Rotation
 * history comes from {@link MovementProcessor} so no extra move listener.
 */
public final class CombatProcessor implements Listener {

    private final KoalaGuard plugin;
    private static final int MAX_SAMPLES = 30;

    public CombatProcessor(KoalaGuard plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onSwing(PlayerAnimationEvent event) {
        if (event.getAnimationType() != PlayerAnimationType.ARM_SWING) return;
        PlayerData data = plugin.getDataManager().get(event.getPlayer());
        if (data == null) return;
        data.lastArmSwingMs = System.currentTimeMillis();
        data.swingsSinceAttack++;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onAttack(EntityDamageByEntityEvent event) {
        if (event.getCause() != EntityDamageEvent.DamageCause.ENTITY_ATTACK) return;
        if (!(event.getDamager() instanceof Player attacker)) return;
        Entity victim = event.getEntity();

        PlayerData data = plugin.getDataManager().get(attacker);
        if (data == null) return;

        long now = System.currentTimeMillis();
        long interval = data.lastAttackMs == 0 ? 0 : now - data.lastAttackMs;

        data.attackTimes.addLast(now);
        while (data.attackTimes.size() > MAX_SAMPLES) data.attackTimes.removeFirst();
        if (interval > 0 && interval < 5000) {
            data.attackIntervals.addLast(interval);
            while (data.attackIntervals.size() > MAX_SAMPLES) data.attackIntervals.removeFirst();
        }

        for (CombatCheck check : plugin.getCheckManager().combat()) {
            try {
                if (check.isEnabled() && !isExempt(attacker)) {
                    check.handle(data, attacker, victim, event);
                }
            } catch (Throwable t) {
                plugin.getLogger().warning("Check " + check.getName() + " error: " + t);
            }
        }

        data.lastAttackMs = now;
        data.lastAttackTarget = victim.getUniqueId();
        data.swingsSinceAttack = 0;
    }

    private boolean isExempt(Player p) {
        return p.hasPermission("koalaguard.bypass")
                || p.getGameMode() == org.bukkit.GameMode.CREATIVE
                || p.getGameMode() == org.bukkit.GameMode.SPECTATOR;
    }
}
