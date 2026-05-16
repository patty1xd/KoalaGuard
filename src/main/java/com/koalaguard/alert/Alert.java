package com.koalaguard.alert;

/**
 * Immutable snapshot of a single violation, consumed by the in-game,
 * console, file and Discord renderers.
 */
public record Alert(
        String player,
        String uuid,
        String check,
        String category,
        String description,
        String detail,
        String world,
        double x, double y, double z,
        int vl,
        int maxVl,
        int ping,
        double tps,
        String brand,
        double severity,        // 0.0 .. 1.0
        boolean punishment,
        String punishmentType,
        long timestamp
) {
    public String coords() {
        return String.format("%.1f, %.1f, %.1f", x, y, z);
    }

    /** Tier name used for colour selection / Discord embed colour. */
    public String tier() {
        if (punishment) return "PUNISH";
        if (severity >= 0.75) return "SEVERE";
        if (severity >= 0.40) return "HIGH";
        return "LOW";
    }
}
