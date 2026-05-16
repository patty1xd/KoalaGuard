package com.koalaguard.manager;

import com.google.gson.*;
import com.koalaguard.KoalaGuard;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.io.*;
import java.time.Instant;
import java.util.*;

public final class BanManager {

    private final KoalaGuard plugin;
    private final File bansFile;
    private final JsonObject bansData;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();

    public BanManager(KoalaGuard plugin) {
        this.plugin = plugin;
        this.bansFile = new File(plugin.getDataFolder(), "bans.json");
        this.bansData = loadBans();
        purgeExpired();
    }

    public void ban(Player player, String reason, String duration) {
        UUID uuid = player.getUniqueId();
        long expiry = parseExpiry(duration);

        JsonObject e = new JsonObject();
        e.addProperty("name", player.getName());
        e.addProperty("uuid", uuid.toString());
        e.addProperty("reason", reason);
        e.addProperty("banned_at", Instant.now().getEpochSecond());
        e.addProperty("expiry", expiry);
        e.addProperty("duration_str", isPermanent(duration) ? "permanent" : duration);
        bansData.add(uuid.toString(), e);
        saveAsync();

        plugin.getLogger().info("Banned " + player.getName() + " for " + reason + " (" + duration + ")");
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (player.isOnline()) player.kick(banScreen(reason, isPermanent(duration) ? "Permanent" : duration));
        });
    }

    public boolean isBanned(UUID uuid) {
        if (uuid == null || !bansData.has(uuid.toString())) return false;
        long expiry = bansData.getAsJsonObject(uuid.toString()).get("expiry").getAsLong();
        if (expiry == -1) return true;
        if (Instant.now().getEpochSecond() > expiry) {
            bansData.remove(uuid.toString());
            saveAsync();
            return false;
        }
        return true;
    }

    public String getBanReason(UUID uuid) {
        if (uuid == null || !bansData.has(uuid.toString())) return "Cheating";
        return bansData.getAsJsonObject(uuid.toString()).get("reason").getAsString();
    }

    public String getBanExpiry(UUID uuid) {
        if (uuid == null || !bansData.has(uuid.toString())) return "Permanent";
        JsonObject e = bansData.getAsJsonObject(uuid.toString());
        return e.get("expiry").getAsLong() == -1 ? "Permanent" : e.get("duration_str").getAsString();
    }

    public Component banScreen(String reason, String duration) {
        return Component.text()
                .append(Component.text("⟦KoalaGuard⟧", TextColor.color(0x4FC3F7), TextDecoration.BOLD))
                .append(Component.newline()).append(Component.newline())
                .append(Component.text("You are banned.", NamedTextColor.RED, TextDecoration.BOLD))
                .append(Component.newline()).append(Component.newline())
                .append(Component.text("Reason: ", NamedTextColor.GRAY))
                .append(Component.text(reason, NamedTextColor.WHITE))
                .append(Component.newline())
                .append(Component.text("Duration: ", NamedTextColor.GRAY))
                .append(Component.text(duration, NamedTextColor.WHITE))
                .append(Component.newline()).append(Component.newline())
                .append(Component.text("Appeal on the server Discord.", NamedTextColor.DARK_GRAY))
                .build();
    }

    public boolean unban(String nameOrUuid) {
        if (nameOrUuid == null || nameOrUuid.isBlank()) return false;
        try {
            UUID uuid = UUID.fromString(nameOrUuid.trim());
            if (bansData.has(uuid.toString())) { bansData.remove(uuid.toString()); saveAsync(); return true; }
        } catch (IllegalArgumentException ignored) {}
        for (Map.Entry<String, JsonElement> en : new ArrayList<>(bansData.entrySet())) {
            JsonObject o = en.getValue().getAsJsonObject();
            if (o.has("name") && o.get("name").getAsString().equalsIgnoreCase(nameOrUuid)) {
                bansData.remove(en.getKey()); saveAsync(); return true;
            }
        }
        return false;
    }

    public List<String> getBannedPlayers() {
        List<String> names = new ArrayList<>();
        for (Map.Entry<String, JsonElement> en : bansData.entrySet()) {
            JsonObject o = en.getValue().getAsJsonObject();
            if (o.has("name")) names.add(o.get("name").getAsString());
        }
        return names;
    }

    private void purgeExpired() {
        long now = Instant.now().getEpochSecond();
        List<String> rm = new ArrayList<>();
        for (Map.Entry<String, JsonElement> en : bansData.entrySet()) {
            long exp = en.getValue().getAsJsonObject().get("expiry").getAsLong();
            if (exp != -1 && now > exp) rm.add(en.getKey());
        }
        rm.forEach(bansData::remove);
        if (!rm.isEmpty()) saveAsync();
    }

    private boolean isPermanent(String d) {
        return d == null || d.isBlank() || d.trim().equalsIgnoreCase("permanent");
    }

    private long parseExpiry(String d) {
        if (isPermanent(d)) return -1;
        long sec = 0;
        StringBuilder num = new StringBuilder();
        for (char c : d.trim().toLowerCase().toCharArray()) {
            if (Character.isDigit(c)) { num.append(c); continue; }
            long n = num.length() > 0 ? Long.parseLong(num.toString()) : 0;
            num.setLength(0);
            switch (c) {
                case 's' -> sec += n;
                case 'm' -> sec += n * 60;
                case 'h' -> sec += n * 3600;
                case 'd' -> sec += n * 86400;
                case 'w' -> sec += n * 604800;
                default -> {}
            }
        }
        return sec <= 0 ? -1 : Instant.now().getEpochSecond() + sec;
    }

    private JsonObject loadBans() {
        if (!bansFile.exists()) { plugin.getDataFolder().mkdirs(); return new JsonObject(); }
        try (Reader r = new FileReader(bansFile)) {
            JsonElement el = JsonParser.parseReader(r);
            return el.isJsonObject() ? el.getAsJsonObject() : new JsonObject();
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to load bans.json: " + e.getMessage());
            return new JsonObject();
        }
    }

    private void saveAsync() {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try (Writer w = new FileWriter(bansFile)) { gson.toJson(bansData, w); }
            catch (Exception e) { plugin.getLogger().warning("Failed to save bans.json: " + e.getMessage()); }
        });
    }
}
