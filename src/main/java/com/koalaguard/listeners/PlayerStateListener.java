package com.koalaguard.listeners;

import com.koalaguard.KoalaGuard;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerVelocityEvent;

public final class PlayerStateListener implements Listener {

  private final KoalaGuard plugin;

  public PlayerStateListener(KoalaGuard plugin) {
    this.plugin = plugin;
  }

  @EventHandler(priority = EventPriority.MONITOR)
  public void onJoin(PlayerJoinEvent event) {
    plugin.getPlayerState().onJoin(event.getPlayer());
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void onTeleport(PlayerTeleportEvent event) {
    plugin.getPlayerState().onTeleport(event.getPlayer());
  }

  @EventHandler(priority = EventPriority.MONITOR)
  public void onVelocity(PlayerVelocityEvent event) {
    plugin.getPlayerState().onVelocity(event.getPlayer(), event.getVelocity());
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void onDamage(EntityDamageEvent event) {
    if (!(event.getEntity() instanceof Player player)) return;
    plugin.getPlayerState().onDamageTaken(player);
  }
}

