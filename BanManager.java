package com.koalaguard.managers;

import com.google.gson.*;
import com.koalaguard.KoalaGuard;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.io.*;
import java.time.Instant;
import java.util.*;

public class BanManager {

    private final KoalaGuard plugin;
    private final File bansFile;
    private final JsonObject bansData;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();

    public BanManager(KoalaGuard plugin) {
        this.plugin = plugin;
        bansFile = new File(plugin.getDataFolder(), "bans.json");
        bansData = loadBans();
        checkExpiredBans();
    }

    public void ban(Player player, String reason, String duration) {
        UUID uuid = player.getUniqueId();
        long expiryEpoch = parseDuration(duration);

        JsonObject entry = new JsonObject();
        entry.addProperty("name", player.getName());
        entry.addProperty("reason", reason);
        entry.addProperty("banned_at", Instant.now().getEpochSecond());
        entry.addProperty("expiry", expiryEpoch); // -1 = permanent
        entry.addProperty("duration_str", duration);

        bansData.add(uuid.toString(), entry);
        saveBans();

        // Public broadcast
        String msg = "§c§l[KoalaGuard] §f" + player.getName() + " §7was punished by §c§lKoalaGuard§7. §8(Reason: " + reason + ")";
        Bukkit.broadcastMessage(msg);

        plugin.getLogger().info("Banned " + player.getName() + " for " + reason + " | Duration: " + duration);

        // Kick on main thread
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (player.isOnline()) {
                player.kickPlayer(
                    "§c§lYou have been banned by KoalaGuard.\n" +
                    "§7Reason: §f" + reason + "\n" +
                    "§7Duration: §f" + (duration.equalsIgnoreCase("permanent") ? "Permanent" : duration) + "\n" +
                    "§7Appeal at your server's discord."
                );
            }
        });
    }

    public boolean isBanned(UUID uuid) {
        if (!bansData.has(uuid.toString())) return false;
        JsonObject entry = bansData.getAsJsonObject(uuid.toString());
        long expiry = entry.get("expiry").getAsLong();
        if (expiry == -1) return true;
        if (Instant.now().getEpochSecond() > expiry) {
            bansData.remove(uuid.toString());
            saveBans();
            return false;
        }
        return true;
    }

    public String getBanReason(UUID uuid) {
        if (!bansData.has(uuid.toString())) return null;
        return bansData.getAsJsonObject(uuid.toString()).get("reason").getAsString();
    }

    public String getBanExpiry(UUID uuid) {
        if (!bansData.has(uuid.toString())) return null;
        JsonObject entry = bansData.getAsJsonObject(uuid.toString());
        long expiry = entry.get("expiry").getAsLong();
        if (expiry == -1) return "Permanent";
        return entry.get("duration_str").getAsString();
    }

    public boolean unban(String playerName) {
        for (Map.Entry<String, JsonElement> entry : bansData.entrySet()) {
            JsonObject obj = entry.getValue().getAsJsonObject();
            if (obj.get("name").getAsString().equalsIgnoreCase(playerName)) {
                bansData.remove(entry.getKey());
                saveBans();
                return true;
            }
        }
        return false;
    }

    public List<String> getBannedPlayers() {
        List<String> names = new ArrayList<>();
        for (Map.Entry<String, JsonElement> entry : bansData.entrySet()) {
            names.add(entry.getValue().getAsJsonObject().get("name").getAsString());
        }
        return names;
    }

    private void checkExpiredBans() {
        long now = Instant.now().getEpochSecond();
        List<String> toRemove = new ArrayList<>();
        for (Map.Entry<String, JsonElement> entry : bansData.entrySet()) {
            long expiry = entry.getValue().getAsJsonObject().get("expiry").getAsLong();
            if (expiry != -1 && now > expiry) toRemove.add(entry.getKey());
        }
        toRemove.forEach(bansData::remove);
        if (!toRemove.isEmpty()) saveBans();
    }

    // Returns epoch second of expiry, or -1 for permanent
    private long parseDuration(String duration) {
        if (duration == null || duration.equalsIgnoreCase("permanent")) return -1;
        long seconds = 0;
        StringBuilder num = new StringBuilder();
        for (char c : duration.toLowerCase().toCharArray()) {
            if (Character.isDigit(c)) {
                num.append(c);
            } else {
                long n = num.length() > 0 ? Long.parseLong(num.toString()) : 0;
                num.setLength(0);
                switch (c) {
                    case 's' -> seconds += n;
                    case 'm' -> seconds += n * 60;
                    case 'h' -> seconds += n * 3600;
                    case 'd' -> seconds += n * 86400;
                    case 'w' -> seconds += n * 604800;
                }
            }
        }
        return Instant.now().getEpochSecond() + seconds;
    }

    private JsonObject loadBans() {
        if (!bansFile.exists()) {
            plugin.getDataFolder().mkdirs();
            return new JsonObject();
        }
        try (Reader reader = new FileReader(bansFile)) {
            JsonElement el = JsonParser.parseReader(reader);
            return el.isJsonObject() ? el.getAsJsonObject() : new JsonObject();
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to load bans.json: " + e.getMessage());
            return new JsonObject();
        }
    }

    private void saveBans() {
        try (Writer writer = new FileWriter(bansFile)) {
            gson.toJson(bansData, writer);
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to save bans.json: " + e.getMessage());
        }
    }
}
