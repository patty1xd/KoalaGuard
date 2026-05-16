package com.koalaguard.data;

import org.bukkit.entity.Player;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Owns the lifecycle of every {@link PlayerData}. One map, cleared on quit —
 * no more leaking per-check {@code Map<UUID,...>} instances.
 */
public final class DataManager {

    private final Map<UUID, PlayerData> data = new ConcurrentHashMap<>();

    public PlayerData create(Player player) {
        PlayerData d = new PlayerData(player);
        try { d.clientBrand = player.getClientBrandName() != null ? player.getClientBrandName() : "unknown"; }
        catch (Throwable ignored) { d.clientBrand = "unknown"; }
        data.put(player.getUniqueId(), d);
        return d;
    }

    public PlayerData get(Player player) {
        if (player == null) return null;
        return data.computeIfAbsent(player.getUniqueId(), k -> {
            PlayerData d = new PlayerData(player);
            try { d.clientBrand = player.getClientBrandName() != null ? player.getClientBrandName() : "unknown"; }
            catch (Throwable ignored) {}
            return d;
        });
    }

    public PlayerData get(UUID uuid) {
        return uuid == null ? null : data.get(uuid);
    }

    public void remove(UUID uuid) {
        if (uuid == null) return;
        PlayerData d = data.remove(uuid);
        if (d != null) d.invalidate();
    }

    public void clear() {
        data.values().forEach(PlayerData::invalidate);
        data.clear();
    }

    public int size() { return data.size(); }
}
