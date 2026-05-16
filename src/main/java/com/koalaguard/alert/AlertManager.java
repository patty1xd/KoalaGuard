package com.koalaguard.alert;

import com.koalaguard.KoalaGuard;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/**
 * Turns an {@link Alert} into a compact, colour-coded, hover-rich in-game
 * message (Adventure), a clean console line, a log line and a Discord embed.
 * No more single bland concatenated string.
 */
public final class AlertManager {

    private static final TextColor BRAND      = TextColor.color(0x4FC3F7);
    private static final TextColor BRAND_DARK = TextColor.color(0x0288D1);
    private static final TextColor MUTE       = TextColor.color(0x9E9E9E);
    private static final TextColor LABEL      = TextColor.color(0x80DEEA);

    private final KoalaGuard plugin;

    public AlertManager(KoalaGuard plugin) {
        this.plugin = plugin;
    }

    private TextColor tierColor(Alert a) {
        return switch (a.tier()) {
            case "PUNISH" -> TextColor.color(0xD32F2F);
            case "SEVERE" -> TextColor.color(0xFF5252);
            case "HIGH"   -> TextColor.color(0xFFA726);
            default        -> TextColor.color(0xFFEE58);
        };
    }

    public int discordColor(Alert a) {
        return switch (a.tier()) {
            case "PUNISH" -> 0xB71C1C;
            case "SEVERE" -> 0xE53935;
            case "HIGH"   -> 0xFB8C00;
            default        -> 0xFDD835;
        };
    }

    private Component prefix() {
        return Component.text()
                .append(Component.text("⟦", BRAND_DARK))
                .append(Component.text("KoalaGuard", BRAND, TextDecoration.BOLD))
                .append(Component.text("⟧ ", BRAND_DARK))
                .build();
    }

    private Component bar(double severity) {
        int filled = (int) Math.round(Math.max(0, Math.min(1, severity)) * 10);
        Component c = Component.empty();
        for (int i = 0; i < 10; i++) {
            c = c.append(Component.text(i < filled ? "▮" : "▯",
                    i < filled ? tierColorFromSeverity(severity) : MUTE));
        }
        return c;
    }

    private TextColor tierColorFromSeverity(double s) {
        if (s >= 0.75) return TextColor.color(0xFF5252);
        if (s >= 0.40) return TextColor.color(0xFFA726);
        return TextColor.color(0xFFEE58);
    }

    private Component kv(String label, Component value) {
        return Component.text()
                .append(Component.text(label + ": ", LABEL))
                .append(value)
                .build();
    }
    private Component kv(String label, String value) {
        return kv(label, Component.text(value, NamedTextColor.WHITE));
    }

    private Component buildHover(Alert a) {
        TextColor tc = tierColor(a);
        return Component.text()
                .append(Component.text("KoalaGuard", BRAND, TextDecoration.BOLD))
                .append(Component.text("  ·  " + a.tier(), tc))
                .append(Component.newline()).append(Component.newline())
                .append(kv("Player", a.player()))
                .append(Component.newline())
                .append(kv("Check", Component.text(a.check() + " ", tc)
                        .append(Component.text("(" + a.category() + ")", MUTE))))
                .append(Component.newline())
                .append(kv("Info", Component.text(a.description(), NamedTextColor.GRAY)))
                .append(Component.newline())
                .append(kv("Violations", Component.text(a.vl() + " / " + a.maxVl(), tc)))
                .append(Component.newline())
                .append(kv("Severity", bar(a.severity())))
                .append(Component.newline())
                .append(kv("Latency", a.ping() < 0 ? "n/a" : a.ping() + " ms"))
                .append(Component.newline())
                .append(kv("Server TPS", String.format("%.2f", a.tps())))
                .append(Component.newline())
                .append(kv("Client", a.brand()))
                .append(Component.newline())
                .append(kv("World", a.world()))
                .append(Component.newline())
                .append(kv("Location", a.coords()))
                .append(a.detail() == null || a.detail().isBlank() ? Component.empty()
                        : Component.text().append(Component.newline())
                            .append(kv("Detail", Component.text(a.detail(), NamedTextColor.AQUA))).build())
                .append(Component.newline()).append(Component.newline())
                .append(Component.text("▸ Click to inspect this player", BRAND))
                .build();
    }

    private Component buildLine(Alert a) {
        TextColor tc = tierColor(a);
        Component core = Component.text()
                .append(prefix())
                .append(Component.text(a.player(), NamedTextColor.WHITE, TextDecoration.BOLD))
                .append(Component.text(" failed ", MUTE))
                .append(Component.text(a.check(), tc, TextDecoration.BOLD))
                .append(Component.text(" ⟨" + a.category() + "⟩ ", MUTE))
                .append(Component.text("×" + a.vl(), tc))
                .append(Component.text("/" + a.maxVl(), MUTE))
                .append(Component.text("  "))
                .append(bar(a.severity()))
                .build();

        return core
                .hoverEvent(HoverEvent.showText(buildHover(a)))
                .clickEvent(ClickEvent.runCommand("/koalaguard info " + a.player()));
    }

    public void sendAlert(Alert a) {
        Component line = buildLine(a);
        String perm = plugin.getConfig().getString("alerts.permission", "koalaguard.alerts");
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.hasPermission(perm)) p.sendMessage(line);
        }
        if (plugin.getConfig().getBoolean("alerts.console", true)) {
            Bukkit.getConsoleSender().sendMessage(line);
        }
        plugin.getLogs().alert(a);
        plugin.getDiscordBot().sendAlert(a);
    }

    public void sendPunishment(Alert a) {
        TextColor tc = tierColor(a);
        Component line = Component.text()
                .append(prefix())
                .append(Component.text(a.player(), NamedTextColor.WHITE, TextDecoration.BOLD))
                .append(Component.text(" was ", MUTE))
                .append(Component.text(a.punishmentType().toUpperCase(), tc, TextDecoration.BOLD))
                .append(Component.text("ed for ", MUTE))
                .append(Component.text(a.check(), tc, TextDecoration.BOLD))
                .build()
                .hoverEvent(HoverEvent.showText(buildHover(a)));

        String perm = plugin.getConfig().getString("alerts.permission", "koalaguard.alerts");
        boolean broadcast = plugin.getConfig().getBoolean("punishments.broadcast", false);
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (broadcast || p.hasPermission(perm)) p.sendMessage(line);
        }
        Bukkit.getConsoleSender().sendMessage(line);
        plugin.getLogs().punishment(a);
        plugin.getDiscordBot().sendAlert(a);
    }
}
