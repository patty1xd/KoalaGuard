package com.koalaguard.command;

import com.koalaguard.KoalaGuard;
import com.koalaguard.engine.replay.ReplayPlayer;
import com.koalaguard.engine.replay.ReplayReader;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.io.File;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class KoalaGuardCommand implements CommandExecutor, TabCompleter {

    private static final TextColor BRAND = TextColor.color(0x4FC3F7);
    private static final DateTimeFormatter LIST_FMT = DateTimeFormatter
            .ofPattern("MMM dd HH:mm").withZone(ZoneId.systemDefault());
    private final KoalaGuard plugin;
    private final Map<UUID, ReplayPlayer> activeReplays = new HashMap<>();

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

        // Per-subcommand permissions — `koalaguard.admin` opens the door,
        // but `unban` / `tp` / `replay` need explicit grants since the broad
        // `admin` perm is often handed to deputies who should only see info.
        String sub = args[0].toLowerCase();
        if ((sub.equals("unban") || sub.equals("tp") || sub.equals("replay"))
                && !sender.hasPermission("koalaguard.admin." + sub)
                && !sender.hasPermission("koalaguard.admin.*")) {
            msg(sender, "Missing permission koalaguard.admin." + sub, NamedTextColor.RED);
            return true;
        }

        switch (sub) {
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
                if (args.length < 2) { msg(sender, "Usage: /kg unban <uuid>", NamedTextColor.RED); return true; }
                // UUID-only to prevent name-collision unban abuse — accept
                // canonical UUID-with-dashes only.
                if (!args[1].matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")) {
                    msg(sender, "unban requires a canonical UUID (use /kg bans to list).", NamedTextColor.RED);
                    return true;
                }
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
            case "replay" -> handleReplay(sender, args);
            case "checks" -> {
                sender.sendMessage(prefix().append(Component.text("Loaded checks:", NamedTextColor.GRAY)));
                plugin.getEngine().all().values().forEach(c ->
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

    private void handleReplay(CommandSender sender, String[] args) {
        if (args.length < 2) {
            msg(sender, "Usage: /kg replay <list|play|stop> [token]", NamedTextColor.RED);
            return;
        }
        switch (args[1].toLowerCase()) {
            case "list" -> {
                File[] files = plugin.getReplayManager().listReplays();
                if (files.length == 0) { msg(sender, "No replays saved yet.", NamedTextColor.GRAY); return; }
                sender.sendMessage(prefix().append(Component.text(
                        "Saved replays (" + files.length + ", newest first):", NamedTextColor.GRAY)));
                int shown = Math.min(files.length, 20);
                for (int i = 0; i < shown; i++) {
                    File f = files[i];
                    sender.sendMessage(Component.text("  " + LIST_FMT.format(
                                    Instant.ofEpochMilli(f.lastModified())) + "  ", NamedTextColor.DARK_GRAY)
                            .append(Component.text(f.getName(), NamedTextColor.WHITE)));
                }
                if (files.length > shown) {
                    msg(sender, "(+" + (files.length - shown) + " older)", NamedTextColor.DARK_GRAY);
                }
            }
            case "play" -> {
                if (!(sender instanceof Player viewer)) { msg(sender, "Players only.", NamedTextColor.RED); return; }
                if (args.length < 3) { msg(sender, "Usage: /kg replay play <name|file>", NamedTextColor.RED); return; }
                File f = plugin.getReplayManager().findReplay(args[2]);
                if (f == null) { msg(sender, "No replay matched: " + args[2], NamedTextColor.RED); return; }

                ReplayPlayer existing = activeReplays.remove(viewer.getUniqueId());
                if (existing != null) existing.stop();

                Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                    try {
                        ReplayReader.Loaded loaded = ReplayReader.read(f);
                        Bukkit.getScheduler().runTask(plugin, () -> {
                            if (!viewer.isOnline()) return;
                            ReplayPlayer rp = new ReplayPlayer(plugin, viewer, loaded.name(), loaded.frames());
                            activeReplays.put(viewer.getUniqueId(), rp);
                            rp.start();
                            msg(viewer, "Replaying " + loaded.name() + " (" + loaded.frames().size()
                                    + " frames, " + loaded.durationMs() + " ms) — reason: "
                                    + (loaded.reason() == null ? "?" : loaded.reason()),
                                    NamedTextColor.GREEN);
                        });
                    } catch (Exception ex) {
                        Bukkit.getScheduler().runTask(plugin, () ->
                                msg(viewer, "Replay load failed: " + ex.getMessage(), NamedTextColor.RED));
                    }
                });
            }
            case "stop" -> {
                if (!(sender instanceof Player viewer)) { msg(sender, "Players only.", NamedTextColor.RED); return; }
                ReplayPlayer rp = activeReplays.remove(viewer.getUniqueId());
                if (rp == null) { msg(sender, "No active replay.", NamedTextColor.GRAY); return; }
                rp.stop();
                msg(sender, "Replay stopped.", NamedTextColor.GREEN);
            }
            default -> msg(sender, "Usage: /kg replay <list|play|stop> [token]", NamedTextColor.RED);
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
        s.sendMessage(Component.text("  /kg replay list|play <token>|stop",
                NamedTextColor.WHITE));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1)
            return List.of("reload", "checks", "info", "tp", "bans", "unban", "discord", "replay");
        if (args.length == 2 && (args[0].equalsIgnoreCase("info")
                || args[0].equalsIgnoreCase("tp") || args[0].equalsIgnoreCase("violations"))) {
            List<String> names = new ArrayList<>();
            plugin.getServer().getOnlinePlayers().forEach(p -> names.add(p.getName()));
            return names;
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("replay")) {
            return List.of("list", "play", "stop");
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("replay")
                && args[1].equalsIgnoreCase("play")) {
            List<String> out = new ArrayList<>();
            for (File f : plugin.getReplayManager().listReplays()) out.add(f.getName());
            return out;
        }
        return List.of();
    }
}
