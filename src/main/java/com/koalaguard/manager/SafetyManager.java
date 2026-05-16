package com.koalaguard.manager;

import com.koalaguard.KoalaGuard;
import com.koalaguard.data.PlayerData;
import org.bukkit.entity.Player;

/**
 * Lag-compensated anti-false-positive gate.
 *
 * Two levels:
 *  • {@link #shouldSuppress} — for movement/combat: dropped while the world
 *    state makes vanilla prediction unreliable (teleport, knockback, damage,
 *    join, respawn, low TPS, transaction latency spike).
 *  • {@link #shouldSuppressBasic} — for checks whose trigger IS damage (e.g.
 *    AutoTotem fires *because* the player just took lethal damage). It must
 *    NOT honour the damage/velocity grace, or the check can never run.
 */
public final class SafetyManager {

    private final KoalaGuard plugin;

    public SafetyManager(KoalaGuard plugin) {
        this.plugin = plugin;
    }

    /** Server/connection unstable enough that physics replication is unsafe. */
    public boolean shouldSuppress(PlayerData data, Player player) {
        if (player == null || data == null) return true;
        if (unstableServer(player)) return true;

        long now = System.currentTimeMillis();
        if (within(now, data.lastTeleportMs,   plugin.getConfig().getLong("safety.teleport-grace-ms", 1500))) return true;
        if (within(now, data.lastVelocityMs,   plugin.getConfig().getLong("safety.velocity-grace-ms",  900))) return true;
        if (within(now, data.lastDamageMs,     plugin.getConfig().getLong("safety.damage-grace-ms",    500))) return true;
        if (within(now, data.joinMs,           plugin.getConfig().getLong("safety.join-grace-ms",     3500))) return true;
        if (within(now, data.lastRespawnMs,    plugin.getConfig().getLong("safety.respawn-grace-ms",  2500))) return true;
        if (within(now, data.gamemodeChangeMs, 1500)) return true;
        if (within(now, data.lastWorldChangeMs, 4000)) return true;
        return within(now, data.lastRiptideMs, 2500);
    }

    /** Only server-health + teleport/world — used by damage-triggered checks. */
    public boolean shouldSuppressBasic(PlayerData data, Player player) {
        if (player == null || data == null) return true;
        if (unstableServer(player)) return true;
        long now = System.currentTimeMillis();
        if (within(now, data.lastTeleportMs, 1500)) return true;
        if (within(now, data.joinMs, plugin.getConfig().getLong("safety.join-grace-ms", 3500))) return true;
        if (within(now, data.lastRespawnMs, 2500)) return true;
        return within(now, data.lastWorldChangeMs, 4000);
    }

    private boolean unstableServer(Player player) {
        double tps = plugin.getMetrics().tps();
        if (tps < plugin.getConfig().getDouble("safety.min-tps", 17.0)) return true;
        int ping = plugin.getMetrics().pingMs(player);
        int maxPing = plugin.getConfig().getInt("safety.max-ping-ms", 350);
        if (ping >= 0 && ping >= maxPing) return true;
        // transaction-measured latency spike (real lag compensation)
        PlayerData d = plugin.getDataManager().get(player);
        if (d != null) {
            int tping = d.transactionPing;
            if (tping > 0 && tping >= maxPing) return true;
        }
        return false;
    }

    private boolean within(long now, long stamp, long window) {
        return stamp > 0 && now - stamp <= window;
    }
}
