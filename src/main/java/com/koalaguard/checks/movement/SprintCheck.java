package com.koalaguard.checks.movement;

import com.koalaguard.KoalaGuard;
import com.koalaguard.checks.Check;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.potion.PotionEffectType;

public class SprintCheck extends Check {
    public SprintCheck(KoalaGuard plugin) { super(plugin, "sprint"); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (!isEnabled()) return;
        Player player = event.getPlayer();
        if (player.hasPermission("koalaguard.bypass")) return;
        if (!player.isSprinting()) return;
        if (player.getGameMode() == GameMode.CREATIVE || player.getGameMode() == GameMode.SPECTATOR) return;

        if (player.getFoodLevel() <= 6) {
            flag(player, "hunger=" + player.getFoodLevel());
        }
        if (player.hasPotionEffect(PotionEffectType.BLINDNESS)) {
            flag(player, "sprinting_while_blind");
        }
    }
}
