package com.koalaguard.listeners;

import com.koalaguard.KoalaGuard;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.*;

public final class PlayerStateListener implements Listener {

    private final KoalaGuard plugin;

    public PlayerStateListener(KoalaGuard plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent e) {
        plugin.getPlayerState().onJoin(e.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent e) {
        plugin.getPlayerState().onTeleport(e.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onVelocity(PlayerVelocityEvent e) {
        plugin.getPlayerState().onVelocity(e.getPlayer(), e.getVelocity());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent e) {
        if (!(e.getEntity() instanceof Player p)) return;
        plugin.getPlayerState().onDamageTaken(p);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerRespawnEvent e) {
        plugin.getPlayerState().onRespawn(e.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onGameModeChange(PlayerGameModeChangeEvent e) {
        plugin.getPlayerState().onGameModeChange(e.getPlayer());
    }

    // Detect elytra landing (transitioning from gliding to on-ground)
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        Player p = e.getPlayer();
        if (e.getTo() == null) return;

        // Riptide: player fires trident with riptide in water/rain
        if (p.isRiptiding()) {
            plugin.getPlayerState().onRiptide(p);
        }

        // Elytra land: was gliding last tick, now on ground
        if (!p.isGliding() && p.isOnGround() && plugin.getPlayerState().recentlyLandedFromElytra(p, 0)) {
            // already marked
        }
        if (p.isGliding()) {
            plugin.getPlayerState().onElytraLand(p); // refresh — cleared when they actually stop
        }

        // Bubble column detection
        Material blockType = e.getTo().getBlock().getType();
        if (blockType == Material.BUBBLE_COLUMN) {
            plugin.getPlayerState().onBubbleColumn(p);
        }

        // Slime block bounce: player moving upward while above slime block
        if (e.getTo().getY() > e.getFrom().getY()) {
            Material below = e.getTo().getBlock().getRelative(0, -1, 0).getType();
            if (below == Material.SLIME_BLOCK) {
                plugin.getPlayerState().onSlimeBounce(p);
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawnFromDeath(PlayerDeathEvent e) {
        // Mark as recently died so respawn grace applies
        // The actual respawn event fires separately
    }
}
