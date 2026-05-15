package com.koalaguard.logging;

import com.koalaguard.KoalaGuard;
import com.koalaguard.discord.DiscordBot;
import org.bukkit.Bukkit;

import java.io.File;
import java.io.FileWriter;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

public final class KoalaGuardLogs {

    private final KoalaGuard plugin;
    private final File logFile;
    private final DiscordBot discord;

    public KoalaGuardLogs(KoalaGuard plugin, DiscordBot discord) {
        this.plugin = plugin;
        this.discord = discord;
        File dir = new File(plugin.getDataFolder(), "logs");
        if (!dir.exists()) dir.mkdirs();
        this.logFile = new File(dir, "koalaguard.log");
    }

    public void alert(String message) {
        String m = stripColors(message);
        write("[ALERT] " + m);
        if (discord != null) discord.sendAlert("`[ALERT]` " + m);
    }

    public void punishment(String message) {
        String m = stripColors(message);
        write("[PUNISH] " + m);
        if (discord != null) discord.sendAlert("**[PUNISH]** " + m);
    }

    private void write(String line) {
        String out = Instant.now() + " " + line + System.lineSeparator();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try (FileWriter w = new FileWriter(logFile, StandardCharsets.UTF_8, true)) {
                w.write(out);
            } catch (Exception e) {
                plugin.getLogger().warning("Failed writing log: " + e.getMessage());
            }
        });
    }

    private static String stripColors(String s) {
        if (s == null) return "";
        return s.replaceAll("§[0-9A-FK-ORa-fk-or]", "");
    }
}
