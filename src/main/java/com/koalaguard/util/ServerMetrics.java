package com.koalaguard.util;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/**
 * Server health metrics. Paper 1.21.11 exposes {@link Player#getPing()} and
 * {@link org.bukkit.Server#getTPS()} directly, so no reflection is needed.
 */
public final class ServerMetrics {

    public int pingMs(Player player) {
        if (player == null) return -1;
        try {
            return player.getPing();
        } catch (Throwable ignored) {
            return -1;
        }
    }

    public double tps() {
        try {
            double[] tps = Bukkit.getServer().getTPS();
            if (tps != null && tps.length > 0) return Math.min(20.0, tps[0]);
        } catch (Throwable ignored) {
        }
        return 20.0;
    }

    /** Approximate ping in ticks (1 tick = 50 ms). */
    public int pingTicks(Player player) {
        int ms = pingMs(player);
        return ms < 0 ? 0 : ms / 50;
    }

    /**
     * Average milliseconds per tick — more sensitive than TPS at detecting
     * tick-loop overload (TPS clamps at 20 and only drops on hard misses;
     * MSPT shows degradation building from 20→48ms long before TPS reacts).
     * Used to widen check tolerances under load and to gate setbacks.
     */
    public double mspt() {
        try {
            return Bukkit.getServer().getAverageTickTime();
        } catch (Throwable ignored) {
            return 50.0;
        }
    }
}
