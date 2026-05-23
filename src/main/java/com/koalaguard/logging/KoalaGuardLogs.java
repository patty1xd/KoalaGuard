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
                safe(a.player()), safe(a.check()), safe(a.category()),
                a.vl(), a.maxVl(), a.ping(), a.tps(),
                safe(a.world()), safe(a.coords()), safe(a.brand()), safe(a.detail())));
    }

    public void punishment(Alert a) {
        write(String.format("[PUNISH] %s -> %s for %s vl=%d ping=%dms tps=%.2f",
                safe(a.player()), safe(a.punishmentType()), safe(a.check()),
                a.vl(), a.ping(), a.tps()));
    }

    /**
     * Strip CR/LF/control characters from attacker-controlled fields before
     * concatenation. Otherwise a malicious client brand of
     * "vanilla\n[ALERT] OtherPlayer failed ban-evasion" forges log lines
     * that fool SIEM / log review (CWE-117 / CWE-93).
     */
    private static String safe(String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\r' || c == '\n' || c == '\t') { b.append(' '); continue; }
            if (c < 0x20 || c == 0x7F) continue;
            b.append(c);
        }
        return b.toString();
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
