package com.koalaguard.checks.movement;

import com.koalaguard.KoalaGuard;
import com.koalaguard.checks.Check;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.potion.PotionEffectType;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class HighJumpCheck extends Check {
    private final Map<UUID, Double> lastY = new HashMap<>();

    public HighJumpCheck(KoalaGuard plugin) { super(plugin, "highjump"); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (!isEnabled()) return;
        Player player = event.getPlayer();
        if (player.hasPermission("koalaguard.bypass")) return;
        if (player.getGameMode() == GameMode.CREATIVE || player.getGameMode() == GameMode.SPECTATOR) return;
        if (player.isFlying() || player.isGliding()) return;

        UUID uuid = player.getUniqueId();
        double prevY = lastY.getOrDefault(uuid, event.getTo().getY());
        double dy = event.getTo().getY() - prevY;
        lastY.put(uuid, event.getTo().getY());

        double maxJump = 0.42;
        if (player.hasPotionEffect(PotionEffectType.JUMP_BOOST)) {
            int amp = player.getPotionEffect(PotionEffectType.JUMP_BOOST).getAmplifier();
            maxJump += 0.1 * (amp + 1);
        }

        if (dy > maxJump * 1.3) {
            flag(player, "dy=" + String.format("%.3f", dy) + " max=" + String.format("%.3f", maxJump));
        }
    }
}
