package com.koalaguard.checks.player;

import com.koalaguard.KoalaGuard;
import com.koalaguard.checks.Check;
import org.bukkit.GameMode;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.entity.EntityRegainHealthEvent;
import org.bukkit.potion.PotionEffectType;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class RegenCheck extends Check {

    private final Map<UUID, Long> lastRegen = new HashMap<>();

    public RegenCheck(KoalaGuard plugin) { super(plugin, "regen"); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onRegen(EntityRegainHealthEvent event) {
        if (!isEnabled()) return;
        if (!(event.getEntity() instanceof Player player)) return;
        if (player.hasPermission("koalaguard.bypass")) return;
        if (player.getGameMode() == GameMode.CREATIVE || player.getGameMode() == GameMode.SPECTATOR) return;

        UUID uuid = player.getUniqueId();
        long now = System.currentTimeMillis();
        long last = lastRegen.getOrDefault(uuid, 0L);

        // Natural regen interval is 80 ticks (4s) at full hunger, min 10 ticks with regen effect
        boolean hasRegen = player.hasPotionEffect(PotionEffectType.REGENERATION);
        long minInterval = hasRegen ? 400L : 3500L;

        if (event.getRegainReason() == EntityRegainHealthEvent.RegainReason.SATIATED
                || event.getRegainReason() == EntityRegainHealthEvent.RegainReason.REGEN) {
            if (now - last < minInterval && last != 0) {
                flag(player, "regen_interval=" + (now - last) + "ms min=" + minInterval + "ms");
            }
            lastRegen.put(uuid, now);
        }
    }
}
