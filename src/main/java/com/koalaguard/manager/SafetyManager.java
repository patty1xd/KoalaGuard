package com.koalaguard.manager;

import com.koalaguard.KoalaGuard;
import com.koalaguard.data.PlayerData;
import org.bukkit.entity.Player;

/**
 * The global anti-false-positive gate. A flag is dropped entirely when the
 * server or connection is in a state where movement/combat prediction is
 * unreliable (lag, recent teleport, knockback, damage, join, respawn…).
 * This is the single most important component for "no false bans".
 */
public final class SafetyManager {

    private final KoalaGuard plugin;

    public SafetyManager(KoalaGuard plugin) {
        this.plugin = plugin;
    }

    public boolean shouldSuppress(PlayerData data, Player player) {
        if (player == null || data == null) return true;

        double tps = plugin.getMetrics().tps();
        if (tps < plugin.getConfig().getDouble("safety.min-tps", 17.5)) return true;

        int ping = plugin.getMetrics().pingMs(player);
        int maxPing = plugin.getConfig().getInt("safety.max-ping-ms", 320);
        if (ping >= 0 && ping >= maxPing) return true;

        long now = System.currentTimeMillis();
        if (within(now, data.lastTeleportMs,   plugin.getConfig().getLong("safety.teleport-grace-ms", 1500))) return true;
        if (within(now, data.lastVelocityMs,   plugin.getConfig().getLong("safety.velocity-grace-ms",  900))) return true;
        if (within(now, data.lastDamageMs,     plugin.getConfig().getLong("safety.damage-grace-ms",    600))) return true;
        if (within(now, data.joinMs,           plugin.getConfig().getLong("safety.join-grace-ms",     3500))) return true;
        if (within(now, data.lastRespawnMs,    plugin.getConfig().getLong("safety.respawn-grace-ms",  2500))) return true;
        if (within(now, data.gamemodeChangeMs, 1500)) return true;
        if (within(now, data.lastWorldChangeMs, 4000)) return true;
        return within(now, data.lastRiptideMs, 2500);
    }

    private boolean within(long now, long stamp, long window) {
        return stamp > 0 && now - stamp <= window;
    }
}
