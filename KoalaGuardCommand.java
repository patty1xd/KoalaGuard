package com.koalaguard.commands;

import com.koalaguard.KoalaGuard;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.*;

public class KoalaGuardCommand implements CommandExecutor, TabCompleter {

    private final KoalaGuard plugin;

    public KoalaGuardCommand(KoalaGuard plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("koalaguard.admin")) {
            sender.sendMessage("§cNo permission.");
            return true;
        }

        if (args.length == 0) {
            sendHelp(sender);
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "reload" -> {
                plugin.reloadConfig();
                sender.sendMessage("§6§l[KoalaGuard] §7Config reloaded.");
            }
            case "bans" -> {
                List<String> banned = plugin.getBanManager().getBannedPlayers();
                if (banned.isEmpty()) {
                    sender.sendMessage("§6§l[KoalaGuard] §7No active bans.");
                } else {
                    sender.sendMessage("§6§l[KoalaGuard] §7Active bans: §f" + String.join(", ", banned));
                }
            }
            case "unban" -> {
                if (args.length < 2) { sender.sendMessage("§cUsage: /kg unban <player>"); return true; }
                boolean success = plugin.getBanManager().unban(args[1]);
                if (success) {
                    sender.sendMessage("§6§l[KoalaGuard] §f" + args[1] + " §7has been unbanned.");
                } else {
                    sender.sendMessage("§6§l[KoalaGuard] §7No ban found for §f" + args[1] + "§7.");
                }
            }
            case "violations" -> {
                if (args.length < 2) { sender.sendMessage("§cUsage: /kg violations <player>"); return true; }
                Player target = plugin.getServer().getPlayer(args[1]);
                if (target == null) { sender.sendMessage("§cPlayer not online."); return true; }
                Map<String, Integer> vls = plugin.getViolationManager().getAllViolations(target.getUniqueId());
                if (vls.isEmpty()) {
                    sender.sendMessage("§6§l[KoalaGuard] §f" + target.getName() + " §7has no violations.");
                } else {
                    sender.sendMessage("§6§l[KoalaGuard] §7Violations for §f" + target.getName() + "§7:");
                    vls.forEach((check, vl) ->
                            sender.sendMessage("  §e" + check + " §7→ §c" + vl));
                }
            }
            default -> sendHelp(sender);
        }
        return true;
    }

    private void sendHelp(CommandSender sender) {
        sender.sendMessage("§6§l[KoalaGuard] §7Commands:");
        sender.sendMessage("  §e/kg reload §7- Reload config");
        sender.sendMessage("  §e/kg bans §7- List active bans");
        sender.sendMessage("  §e/kg unban <player> §7- Unban a player");
        sender.sendMessage("  §e/kg violations <player> §7- View a player's VLs");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) return List.of("reload", "bans", "unban", "violations");
        if (args.length == 2 && (args[0].equalsIgnoreCase("violations"))) {
            List<String> names = new ArrayList<>();
            plugin.getServer().getOnlinePlayers().forEach(p -> names.add(p.getName()));
            return names;
        }
        return List.of();
    }
}
