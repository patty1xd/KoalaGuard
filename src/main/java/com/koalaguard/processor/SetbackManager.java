package com.koalaguard.processor;

import com.koalaguard.KoalaGuard;
import com.koalaguard.data.PlayerData;
import org.bukkit.Location;
import org.bukkit.entity.Player;

/**
 * Setback / lagback — the defining Grim-style response.
 *
 * Movement checks don't merely add VL on an egregious physics offset; they
 * request a setback. The player is teleported back to their last
 * prediction-valid position, which NEUTRALISES the cheat in real time even
 * while VL stays conservative. A teleport grace is stamped so the snap-back
 * itself can't cascade into more flags, and re-used setback velocity is
 * ignored (the exploit Grim documents).
 */
public final class SetbackManager {

    private final KoalaGuard plugin;

    public SetbackManager(KoalaGuard plugin) {
        this.plugin = plugin;
    }

    /**
     * Remember a position the engine accepted. Only advances on a SUPPORTED
     * position (ground/near-ground) so a Fly/Speed setback yanks the player
     * back to the last legitimately-reachable spot, not to mid-cheat.
     */
    public void markValid(PlayerData d, Player p, boolean safe) {
        if (d.setbackPending || !safe) return;
        Location l = p.getLocation();
        if (d.lastValidLocation == null
                || d.lastValidLocation.getWorld() != l.getWorld()
                || d.lastValidLocation.distanceSquared(l) > 0.0001) {
            d.lastValidLocation = l.clone();
        }
    }

    /** Teleport the player back to the last valid spot (main thread). */
    public void requestSetback(PlayerData d, Player p, String reason) {
        long now = System.currentTimeMillis();
        if (d.setbackPending) return;
        if (now - d.lastSetbackMs < 400) return;            // de-dupe burst
        Location to = d.lastValidLocation;
        if (to == null || to.getWorld() == null) {
            // No recorded anchor (the player was never on a sim-valid ground
            // frame before cheating — true for clip/fly/void where the cheat
            // starts immediately). Fall back to solid ground beneath them so a
            // setback ALWAYS happens instead of silently degrading to an
            // alert-only flag.
            to = groundBelow(p.getLocation());
        }
        if (to == null || to.getWorld() == null) return;

        final Location target = to;
        d.setbackPending = true;
        d.lastSetbackMs = now;
        d.setbackStreak++;

        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (!p.isOnline() || !d.isAlive()) { d.setbackPending = false; return; }
            d.lastTeleportMs = System.currentTimeMillis();   // grace so the snap-back is ignored
            Location safe = target.clone();
            safe.setYaw(p.getLocation().getYaw());
            safe.setPitch(p.getLocation().getPitch());
            p.setVelocity(new org.bukkit.util.Vector(0, 0, 0));
            p.teleport(safe);
            d.setbackPending = false;
            if (debug()) plugin.getLogger().info("[Setback] " + p.getName() + " -> " + reason);
        });
    }

    /**
     * Scan straight down for the first solid block and return the standing
     * spot on top of it. Used only as the no-anchor fallback so a setback is
     * never silently skipped.
     */
    private Location groundBelow(Location from) {
        if (from == null || from.getWorld() == null) return null;
        Location l = from.clone();
        int minY = from.getWorld().getMinHeight();
        for (int i = 0; i < 64 && l.getBlockY() > minY; i++) {
            Location below = l.clone().subtract(0, 1, 0);
            if (below.getBlock().getType().isSolid()) {
                l.setY(below.getBlockY() + 1.0);
                return l;
            }
            l = below;
        }
        return null;
    }

    private boolean debug() {
        return plugin.getConfig().getBoolean("debug", false);
    }
}
