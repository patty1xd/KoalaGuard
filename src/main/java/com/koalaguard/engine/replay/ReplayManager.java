package com.koalaguard.engine.replay;

import com.koalaguard.KoalaGuard;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Lifecycle owner for per-player {@link ReplayBuffer}s and the gateway through
 * which every replay frame is recorded / saved.
 *
 * <ul>
 *   <li>{@code start} — called from {@code BukkitStateListener.onJoin}; opens
 *       a buffer and stamps a {@code SPAWN} frame with the player's current
 *       pose so even a player who never moves still has a reconstructable
 *       starting point.</li>
 *   <li>{@code stop}  — clears the buffer on quit.</li>
 *   <li>{@code record} — called from the netty packet listener AND from a few
 *       Bukkit handlers (damage, death). Cheap, lock-free per buffer.</li>
 *   <li>{@code saveOnBan} — invoked by {@link com.koalaguard.manager.PunishmentManager}
 *       the moment a ban punishment is applied; writes the rolling window to
 *       {@code plugins/KoalaGuard/replays/<name>_<timestamp>_<uuid>.kgr.gz}
 *       on an async thread and then clears the buffer.</li>
 * </ul>
 *
 * All disk work is async — the in-game ban kick is never delayed by I/O.
 */
public final class ReplayManager {

    private static final DateTimeFormatter STAMP = DateTimeFormatter
            .ofPattern("yyyyMMdd-HHmmss").withZone(ZoneId.systemDefault());

    private final KoalaGuard plugin;
    private final Map<UUID, ReplayBuffer> buffers = new ConcurrentHashMap<>();
    private final File dir;
    private final int windowSeconds;
    private final boolean enabled;
    private final boolean saveOnBan;

    public ReplayManager(KoalaGuard plugin) {
        this.plugin = plugin;
        this.enabled = plugin.getConfig().getBoolean("replay.enabled", true);
        this.windowSeconds = Math.max(5,
                plugin.getConfig().getInt("replay.window-seconds", 30));
        this.saveOnBan = plugin.getConfig().getBoolean("replay.save-on-ban", true);
        this.dir = new File(plugin.getDataFolder(), "replays");
        if (enabled) this.dir.mkdirs();
    }

    public boolean isEnabled() { return enabled; }
    public int windowSeconds() { return windowSeconds; }
    public File directory() { return dir; }

    public void start(Player p) {
        if (!enabled || p == null) return;
        ReplayBuffer b = new ReplayBuffer(windowSeconds);
        Location loc = p.getLocation();
        ReplayFrame spawn = new ReplayFrame(System.nanoTime(), ReplayKind.SPAWN);
        spawn.x = loc.getX(); spawn.y = loc.getY(); spawn.z = loc.getZ();
        spawn.yaw = loc.getYaw(); spawn.pitch = loc.getPitch();
        spawn.onGround = true;
        b.push(spawn);
        buffers.put(p.getUniqueId(), b);
    }

    public void stop(UUID uuid) {
        if (uuid == null) return;
        ReplayBuffer b = buffers.remove(uuid);
        if (b != null) b.clear();
    }

    public void record(UUID uuid, ReplayFrame f) {
        if (!enabled || uuid == null || f == null) return;
        ReplayBuffer b = buffers.get(uuid);
        if (b != null) b.push(f);
    }

    /**
     * Snapshot + async serialise + clear. Called once per banned player.
     * The buffer is intentionally cleared after the save so a re-ban of the
     * same UUID in the same session doesn't accidentally re-write the same
     * window.
     */
    public void saveOnBan(Player p, String reason) {
        if (!enabled || !saveOnBan || p == null) return;
        UUID uuid = p.getUniqueId();
        ReplayBuffer b = buffers.get(uuid);
        if (b == null) return;
        var frames = b.snapshot();
        if (frames.isEmpty()) return;
        String name = p.getName();
        String safeName = name == null ? "unknown" : name.replaceAll("[^A-Za-z0-9_]", "_");
        String stamp = STAMP.format(Instant.now());
        File out = new File(dir, safeName + "_" + stamp + "_" + uuid + ".kgr.gz");
        b.clear();

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                ReplayWriter.write(out, uuid, name, reason, frames);
                plugin.getLogger().info("Saved " + frames.size() + "-frame replay for "
                        + name + " → " + out.getName());
            } catch (IOException e) {
                plugin.getLogger().warning("Failed to save replay for " + name
                        + ": " + e.getMessage());
            }
        });
    }

    /** Lists newest-first. */
    public File[] listReplays() {
        if (!dir.isDirectory()) return new File[0];
        File[] files = dir.listFiles((d, n) -> n.toLowerCase(Locale.ROOT).endsWith(".kgr.gz"));
        if (files == null) return new File[0];
        Arrays.sort(files, (a, c) -> Long.compare(c.lastModified(), a.lastModified()));
        return files;
    }

    /**
     * Resolves a user-supplied identifier against the replays directory.
     * Accepts (in order): exact filename, filename prefix, UUID substring,
     * player-name prefix. Returns the most-recent match.
     */
    public File findReplay(String token) {
        if (token == null || token.isBlank()) return null;
        File[] all = listReplays();
        if (all.length == 0) return null;
        String t = token.toLowerCase(Locale.ROOT);
        for (File f : all) if (f.getName().equalsIgnoreCase(token)) return f;
        for (File f : all) if (f.getName().toLowerCase(Locale.ROOT).startsWith(t)) return f;
        for (File f : all) if (f.getName().toLowerCase(Locale.ROOT).contains(t)) return f;
        return null;
    }
}
