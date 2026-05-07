package com.koalaguard.checks.combat;

import com.koalaguard.KoalaGuard;
import com.koalaguard.checks.Check;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.potion.PotionEffectType;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class CriticalsCheck extends Check {

    private final Map<UUID, Integer> critStreak = new HashMap<>();

    public CriticalsCheck(KoalaGuard plugin) { super(plugin, "criticals"); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        if (!isEnabled()) return;
        if (!(event.getDamager() instanceof Player player)) return;
        if (player.hasPermission("koalaguard.bypass")) return;

        // A critical hit requires the player to be falling (not on ground)
        // If they're on ground but dealing critical-level damage repeatedly, flag
        boolean onGround = player.isOnGround();
        boolean hasStrength = player.hasPotionEffect(PotionEffectType.STRENGTH);
        boolean hasWeakness = player.hasPotionEffect(PotionEffectType.WEAKNESS);

        // Criticals require being in air (falling)
        if (onGround && !player.isFlying()) {
            UUID uuid = player.getUniqueId();
            // We check if damage is unusually high for a grounded hit
            double maxExpected = hasStrength ? 12.0 : 8.0;
            if (hasWeakness) maxExpected /= 2;

            if (event.getDamage() > maxExpected) {
                int streak = critStreak.merge(uuid, 1, Integer::sum);
                if (streak >= 5) {
                    flag(player, "crit_on_ground streak=" + streak);
                    critStreak.put(uuid, 0);
                }
            } else {
                critStreak.put(uuid, 0);
            }
        }
    }
}
