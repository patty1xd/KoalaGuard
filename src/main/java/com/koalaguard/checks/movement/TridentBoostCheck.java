package com.koalaguard.checks.movement;

import com.koalaguard.KoalaGuard;
import com.koalaguard.checks.Check;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.enchantments.Enchantment;

public class TridentBoostCheck extends Check {
    public TridentBoostCheck(KoalaGuard plugin) { super(plugin, "tridentboost"); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (!isEnabled()) return;
        Player player = event.getPlayer();
        if (player.hasPermission("koalaguard.bypass")) return;

        double dx = event.getTo().getX() - event.getFrom().getX();
        double dy = event.getTo().getY() - event.getFrom().getY();
        double dz = event.getTo().getZ() - event.getFrom().getZ();
        double speed = Math.sqrt(dx*dx + dy*dy + dz*dz);

        // Only flag extreme speeds not explained by riptide
        if (speed > 5.0) {
            ItemStack mainHand = player.getInventory().getItemInMainHand();
            boolean hasRiptide = mainHand.getEnchantmentLevel(Enchantment.RIPTIDE) > 0;
            if (!hasRiptide) {
                flag(player, "speed=" + String.format("%.2f", speed));
            }
        }
    }
}
