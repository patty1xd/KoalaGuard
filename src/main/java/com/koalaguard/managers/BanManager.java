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
    this.bansFile = new File(plugin.getDataFolder(), "bans.json");
    this.bansData = loadBans();
    checkExpiredBans();
  }

  public void ban(Player player, String reason, String duration) {
    UUID uuid = player.getUniqueId();
    long expiryEpoch = parseDurationToExpiryEpoch(duration);

    JsonObject entry = new JsonObject();
    entry.addProperty("name", player.getName());
    entry.addProperty("uuid", uuid.toString());
    entry.addProperty("reason", reason);
    entry.addProperty("banned_at", Instant.now().getEpochSecond());
    entry.addProperty("expiry", expiryEpoch); // -1 = permanent
    entry.addProperty("duration_str", duration == null ? "permanent" : duration);

    bansData.add(uuid.toString(), entry);
    saveBansAsync();

    String msg =
        "§c§l[KoalaGuard] §f"
            + player.getName()
            + " §7was punished by §c§lKoalaGuard§7. §8(Reason: "
            + reason
            + ")";
    boolean broadcast = plugin.getConfig().getBoolean("punishments.broadcast", false);
    if (broadcast) Bukkit.broadcastMessage(msg);

    plugin.getLogger().info("Banned " + player.getName() + " for " + reason + " | Duration: " + duration);

    Bukkit.getScheduler()
        .runTask(
            plugin,
            () -> {
              if (player.isOnline()) {
                player.kickPlayer(
                    "§c§lYou have been banned by KoalaGuard.\n"
                        + "§7Reason: §f"
                        + reason
                        + "\n"
                        + "§7Duration: §f"
                        + (isPermanent(duration) ? "Permanent" : duration)
                        + "\n"
                        + "§7Appeal at your server's discord.");
              }
            });
  }

  public boolean isBanned(UUID uuid) {
    if (uuid == null) return false;
    if (!bansData.has(uuid.toString())) return false;

    JsonObject entry = bansData.getAsJsonObject(uuid.toString());
    long expiry = entry.get("expiry").getAsLong();

    if (expiry == -1) return true;

    if (Instant.now().getEpochSecond() > expiry) {
      bansData.remove(uuid.toString());
      saveBansAsync();
      return false;
    }
    return true;
  }

  public String getBanReason(UUID uuid) {
    if (uuid == null || !bansData.has(uuid.toString())) return null;
    return bansData.getAsJsonObject(uuid.toString()).get("reason").getAsString();
  }

  public String getBanExpiry(UUID uuid) {
    if (uuid == null || !bansData.has(uuid.toString())) return null;

    JsonObject entry = bansData.getAsJsonObject(uuid.toString());
    long expiry = entry.get("expiry").getAsLong();
    if (expiry == -1) return "Permanent";

    // Return stored human string; you could also format the epoch into a date here.
    return entry.get("duration_str").getAsString();
  }

  public boolean unban(String playerNameOrUuid) {
    if (playerNameOrUuid == null || playerNameOrUuid.isBlank()) return false;

    // UUID direct unban
    try {
      UUID uuid = UUID.fromString(playerNameOrUuid.trim());
      if (bansData.has(uuid.toString())) {
        bansData.remove(uuid.toString());
        saveBansAsync();
        return true;
      }
    } catch (IllegalArgumentException ignored) {
      // not a UUID -> fall through to name search
    }

    // Name search (legacy)
    for (Map.Entry<String, JsonElement> entry : bansData.entrySet()) {
      JsonObject obj = entry.getValue().getAsJsonObject();
      if (obj.has("name") && obj.get("name").getAsString().equalsIgnoreCase(playerNameOrUuid)) {
        bansData.remove(entry.getKey());
        saveBansAsync();
        return true;
      }
    }
    return false;
  }

  public List<String> getBannedPlayers() {
    List<String> names = new ArrayList<>();
    for (Map.Entry<String, JsonElement> entry : bansData.entrySet()) {
      JsonObject obj = entry.getValue().getAsJsonObject();
      if (obj.has("name")) names.add(obj.get("name").getAsString());
    }
    return names;
  }

  private void checkExpiredBans() {
    long now = Instant.now().getEpochSecond();
    List<String> toRemove = new ArrayList<>();

    for (Map.Entry<String, JsonElement> entry : bansData.entrySet()) {
      JsonObject obj = entry.getValue().getAsJsonObject();
      long expiry = obj.get("expiry").getAsLong();
      if (expiry != -1 && now > expiry) toRemove.add(entry.getKey());
    }

    toRemove.forEach(bansData::remove);
    if (!toRemove.isEmpty()) saveBansAsync();
  }

  private boolean isPermanent(String duration) {
    return duration == null || duration.isBlank() || duration.trim().equalsIgnoreCase("permanent");
  }

  // Returns epoch second of expiry, or -1 for permanent
  private long parseDurationToExpiryEpoch(String duration) {
    if (isPermanent(duration)) return -1;

    long seconds = 0;
    StringBuilder num = new StringBuilder();

    for (char c : duration.trim().toLowerCase().toCharArray()) {
      if (Character.isDigit(c)) {
        num.append(c);
        continue;
      }

      long n = num.length() > 0 ? Long.parseLong(num.toString()) : 0;
      num.setLength(0);

      switch (c) {
        case 's' -> seconds += n;
        case 'm' -> seconds += n * 60;
        case 'h' -> seconds += n * 3600;
        case 'd' -> seconds += n * 86400;
        case 'w' -> seconds += n * 604800;
        default -> {
          // ignore unknown suffix chars
        }
      }
    }

    // If it ended with digits and no suffix, ignore those digits (safer than guessing).
    if (seconds <= 0) return -1;

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

  private void saveBansAsync() {
    Bukkit.getScheduler().runTaskAsynchronously(plugin, this::saveBansSync);
  }

  private void saveBansSync() {
    try (Writer writer = new FileWriter(bansFile)) {
      gson.toJson(bansData, writer);
    } catch (Exception e) {
      plugin.getLogger().warning("Failed to save bans.json: " + e.getMessage());
    }
  }
}
