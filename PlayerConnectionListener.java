package com.koalaguard.listeners;

import com.koalaguard.KoalaGuard;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerLoginEvent;
import org.bukkit.event.player.PlayerQuitEvent;

public class PlayerConnectionListener implements Listener {

    private final KoalaGuard plugin;

    public PlayerConnectionListener(KoalaGuard plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onLogin(PlayerLoginEvent event) {
        if (plugin.getBanManager().isBanned(event.getPlayer().getUniqueId())) {
            String reason = plugin.getBanManager().getBanReason(event.getPlayer().getUniqueId());
            String expiry = plugin.getBanManager().getBanExpiry(event.getPlayer().getUniqueId());
            event.disallow(PlayerLoginEvent.Result.KICK_BANNED,
                "§c§lBanned by KoalaGuard\n§7Reason: §f" + reason + "\n§7Duration: §f" + expiry);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        plugin.getViolationManager().clearPlayer(event.getPlayer().getUniqueId());
    }
}
