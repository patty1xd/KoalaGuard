package com.koalaguard.command;

import com.koalaguard.KoalaGuard;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class KoalaGuardCommand implements CommandExecutor, TabCompleter {

    private static final TextColor BRAND = TextColor.color(0x4FC3F7);
    private final KoalaGuard plugin;

    public KoalaGuardCommand(KoalaGuard plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("koalaguard.admin")) {
            sender.sendMessage(Component.text("No permission.", NamedTextColor.RED));
            return true;
        }
        if (args.length == 0) { help(sender); return true; }

        switch (args[0].toLowerCase()) {
            case "reload" -> {
                plugin.reloadConfig();
                plugin.getDiscordBot().reloadConfig();
                msg(sender, "Configuration reloaded.", NamedTextColor.GREEN);
            }
            case "bans" -> {
                List<String> banned = plugin.getBanManager().getBannedPlayers();
                msg(sender, banned.isEmpty() ? "No active bans."
                        : "Active bans (" + banned.size() + "): " + String.join(", ", banned),
                        NamedTextColor.GRAY);
            }
            case "unban" -> {
                if (args.length < 2) { msg(sender, "Usage: /kg unban <player|uuid>", NamedTextColor.RED); return true; }
                boolean ok = plugin.getBanManager().unban(args[1]);
                msg(sender, ok ? args[1] + " unbanned." : "No ban found for " + args[1] + ".",
                        ok ? NamedTextColor.GREEN : NamedTextColor.RED);
            }
            case "violations", "info" -> {
                if (args.length < 2) { msg(sender, "Usage: /kg info <player>", NamedTextColor.RED); return true; }
                Player t = plugin.getServer().getPlayerExact(args[1]);
                if (t == null) { msg(sender, "Player not online.", NamedTextColor.RED); return true; }
                showInfo(sender, t);
            }
            case "tp" -> {
                if (!(sender instanceof Player p)) { msg(sender, "Players only.", NamedTextColor.RED); return true; }
                if (args.length < 2) { msg(sender, "Usage: /kg tp <player>", NamedTextColor.RED); return true; }
                Player t = plugin.getServer().getPlayerExact(args[1]);
                if (t == null) { msg(sender, "Player not online.", NamedTextColor.RED); return true; }
                p.teleport(t);
                msg(sender, "Teleported to " + t.getName() + ".", NamedTextColor.GREEN);
            }
            case "discord" -> msg(sender, "Discord: " + (plugin.getDiscordBot().isConnected()
                    ? "connected" : "disconnected"),
                    plugin.getDiscordBot().isConnected() ? NamedTextColor.GREEN : NamedTextColor.RED);
            case "checks" -> {
                sender.sendMessage(prefix().append(Component.text("Loaded checks:", NamedTextColor.GRAY)));
                plugin.getCheckManager().all().values().forEach(c ->
                        sender.sendMessage(Component.text("  • ", BRAND)
                                .append(Component.text(c.getName(), NamedTextColor.WHITE))
                                .append(Component.text(" (" + c.getCategory().display() + ") ", NamedTextColor.DARK_GRAY))
                                .append(Component.text(c.isEnabled() ? "on" : "off",
                                        c.isEnabled() ? NamedTextColor.GREEN : NamedTextColor.RED))));
            }
            default -> help(sender);
        }
        return true;
    }

    private void showInfo(CommandSender sender, Player t) {
        Map<String, Integer> vls = plugin.getViolationManager().getAllViolations(t.getUniqueId());
        sender.sendMessage(prefix().append(Component.text("Report — ", NamedTextColor.GRAY))
                .append(Component.text(t.getName(), NamedTextColor.WHITE, TextDecoration.BOLD)));
        sender.sendMessage(Component.text("  Ping ", NamedTextColor.DARK_GRAY)
                .append(Component.text(plugin.getMetrics().pingMs(t) + "ms", NamedTextColor.WHITE))
                .append(Component.text("  TPS ", NamedTextColor.DARK_GRAY))
                .append(Component.text(String.format("%.2f", plugin.getMetrics().tps()), NamedTextColor.WHITE)));
        if (vls.isEmpty()) {
            sender.sendMessage(Component.text("  No active violations.", NamedTextColor.GREEN));
        } else {
            vls.forEach((c, v) -> sender.sendMessage(Component.text("  • ", BRAND)
                    .append(Component.text(c, NamedTextColor.YELLOW))
                    .append(Component.text(" → ", NamedTextColor.DARK_GRAY))
                    .append(Component.text(String.valueOf(v), NamedTextColor.RED))));
        }
    }

    private Component prefix() {
        return Component.text("⟦KoalaGuard⟧ ", BRAND, TextDecoration.BOLD);
    }

    private void msg(CommandSender s, String text, NamedTextColor c) {
        s.sendMessage(prefix().append(Component.text(text, c)));
    }

    private void help(CommandSender s) {
        msg(s, "Commands:", NamedTextColor.GRAY);
        s.sendMessage(Component.text("  /kg reload  /kg checks  /kg info <p>  /kg tp <p>",
                NamedTextColor.WHITE));
        s.sendMessage(Component.text("  /kg bans  /kg unban <p|uuid>  /kg discord",
                NamedTextColor.WHITE));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1)
            return List.of("reload", "checks", "info", "tp", "bans", "unban", "discord");
        if (args.length == 2 && (args[0].equalsIgnoreCase("info")
                || args[0].equalsIgnoreCase("tp") || args[0].equalsIgnoreCase("violations"))) {
            List<String> names = new ArrayList<>();
            plugin.getServer().getOnlinePlayers().forEach(p -> names.add(p.getName()));
            return names;
        }
        return List.of();
    }
}
