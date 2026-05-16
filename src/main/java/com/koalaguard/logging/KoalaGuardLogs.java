package com.koalaguard.logging;

import com.koalaguard.KoalaGuard;
import com.koalaguard.alert.Alert;
import org.bukkit.Bukkit;

import java.io.File;
import java.io.FileWriter;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public final class KoalaGuardLogs {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final KoalaGuard plugin;
    private final File logFile;

    public KoalaGuardLogs(KoalaGuard plugin) {
        this.plugin = plugin;
        File dir = new File(plugin.getDataFolder(), "logs");
        if (!dir.exists()) dir.mkdirs();
        this.logFile = new File(dir, "violations.log");
    }

    public void alert(Alert a) {
        write(String.format("[ALERT] %s failed %s (%s) vl=%d/%d ping=%dms tps=%.2f world=%s loc=%s brand=%s | %s",
                a.player(), a.check(), a.category(), a.vl(), a.maxVl(), a.ping(), a.tps(),
                a.world(), a.coords(), a.brand(), a.detail()));
    }

    public void punishment(Alert a) {
        write(String.format("[PUNISH] %s -> %s for %s vl=%d ping=%dms tps=%.2f",
                a.player(), a.punishmentType(), a.check(), a.vl(), a.ping(), a.tps()));
    }

    private void write(String line) {
        String out = "[" + LocalDateTime.now().format(TS) + "] " + line + System.lineSeparator();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try (FileWriter w = new FileWriter(logFile, StandardCharsets.UTF_8, true)) {
                w.write(out);
            } catch (Exception e) {
                plugin.getLogger().warning("Failed writing log: " + e.getMessage());
            }
        });
    }
}
