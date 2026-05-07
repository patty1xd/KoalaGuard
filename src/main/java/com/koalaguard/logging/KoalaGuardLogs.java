package com.koalaguard.logging;

import com.koalaguard.KoalaGuard;
import org.bukkit.Bukkit;

import java.io.File;
import java.io.FileWriter;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

public final class KoalaGuardLogs {
  private final KoalaGuard plugin;
  private final File logFile;
  private final DiscordWebhookNotifier discord;

  public KoalaGuardLogs(KoalaGuard plugin) {
    this.plugin = plugin;
    File dir = new File(plugin.getDataFolder(), "KoalaGuardLogs");
    if (!dir.exists()) dir.mkdirs();
    this.logFile = new File(dir, "KoalaGuardLogs.log");
    this.discord = new DiscordWebhookNotifier(plugin);
  }

  public void alert(String message) {
    String m = stripColors(message);
    write("[ALERT] " + m);
    discord.send("[ALERT] " + m);
  }

  public void punishment(String message) {
    String m = stripColors(message);
    write("[PUNISH] " + m);
    discord.send("[PUNISH] " + m);
  }

  private void write(String line) {
    String out = Instant.now() + " " + line + System.lineSeparator();
    Bukkit.getScheduler()
        .runTaskAsynchronously(
            plugin,
            () -> {
              try (FileWriter w = new FileWriter(logFile, StandardCharsets.UTF_8, true)) {
                w.write(out);
              } catch (Exception e) {
                plugin.getLogger().warning("Failed writing KoalaGuardLogs: " + e.getMessage());
              }
            });
  }

  private static String stripColors(String s) {
    if (s == null) return "";
    return s.replaceAll("§[0-9A-FK-ORa-fk-or]", "");
  }
}

