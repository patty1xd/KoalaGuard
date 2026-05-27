package com.koalaguard.manager;

import com.koalaguard.KoalaGuard;
import com.koalaguard.alert.Alert;
import com.koalaguard.check.Check;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/**
 * Applies the configured punishment, automatically downgrading to a softer
 * action when the server is unstable and refusing bans unless explicitly
 * enabled — the "no false bans" guarantee.
 */
public final class PunishmentManager {

    private final KoalaGuard plugin;

    public PunishmentManager(KoalaGuard plugin) {
        this.plugin = plugin;
    }

    public void punish(Check check, Player player, int vl, double severity, String detail) {
        int ping = plugin.getMetrics().pingMs(player);
        double tps = plugin.getMetrics().tps();

        boolean allowBans = plugin.getConfig().getBoolean("safety.allow-bans", false);
        int maxPing = plugin.getConfig().getInt("safety.max-ping-ms", 320);
        double minTps = plugin.getConfig().getDouble("safety.min-tps", 17.5);
        boolean unstable = (ping >= 0 && ping >= maxPing) || (tps < minTps);

        String p = check.punishment() == null ? "kick" : check.punishment().toLowerCase();
        if (unstable) p = plugin.getConfig().getString("safety.unstable-downgrade", "warn").toLowerCase();
        if (p.equals("ban") && !allowBans) p = "kick";

        Alert alert = plugin.getViolationManager()
                .buildAlert(check, player, detail, vl, check.maxViolations(), severity, true, p);

        switch (p) {
            case "ban" -> {
                String duration = check.banDuration();
                // Snapshot the rolling buffer BEFORE the ban — once the kick
                // packet flushes, the player session can be torn down at any
                // time and the buffer with it.
                plugin.getReplayManager().saveOnBan(player, check.getName());
                plugin.getBanManager().ban(player, check.getName(), duration);
                plugin.getAlertManager().sendPunishment(alert);
            }
            case "warn" -> {
                player.sendMessage(Component.text()
                        .append(Component.text("⚠ ", TextColor.color(0xFFA726)))
                        .append(Component.text("Suspicious activity detected (" + check.getName() + ").",
                                NamedTextColor.RED))
                        .build());
                plugin.getAlertManager().sendPunishment(alert);
            }
            case "kick" -> {
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (player.isOnline()) player.kick(kickScreen(check.getName()));
                });
                plugin.getAlertManager().sendPunishment(alert);
            }
            default -> { /* unknown -> no-op (safe) */ }
        }
    }

    private Component kickScreen(String check) {
        return Component.text()
                .append(Component.text("⟦KoalaGuard⟧", TextColor.color(0x4FC3F7), TextDecoration.BOLD))
                .append(Component.newline()).append(Component.newline())
                .append(Component.text("You were removed for ", NamedTextColor.GRAY))
                .append(Component.text(check, NamedTextColor.RED, TextDecoration.BOLD))
                .append(Component.newline()).append(Component.newline())
                .append(Component.text("If you believe this is a mistake, appeal on Discord.",
                        NamedTextColor.DARK_GRAY))
                .build();
    }
}
