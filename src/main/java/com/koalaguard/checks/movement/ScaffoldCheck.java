package com.koalaguard.checks.movement;

import com.koalaguard.KoalaGuard;
import com.koalaguard.checks.Check;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.BlockPlaceEvent;

import java.util.*;

/**
 * Detects Scaffold (auto-bridging) cheats.
 *
 * Legitimate bridging:
 *   - Player crouches, looks down/back, places blocks manually.
 *   - Maximum ~5-6 blocks/second even with practiced bridging.
 *
 * Scaffold cheat patterns:
 *   A) High block-place rate (> threshold/sec) while moving forward in air.
 *   B) Blocks placed directly under the player's feet while looking straight
 *      ahead (pitch > -20°) — impossible without automation.
 *   C) Player places blocks while in the air for too many consecutive placements.
 */
public class ScaffoldCheck extends Check {

    // Per-player placement timestamps
    private final Map<UUID, List<Long>>  placeTimes  = new HashMap<>();
    // How many of the last placements were "under feet while looking forward"
    private final Map<UUID, Integer>     aheadStreak = new HashMap<>();
    // Air-placements streak (placing while not touching the ground)
    private final Map<UUID, Integer>     airPlaceStreak = new HashMap<>();

    public ScaffoldCheck(KoalaGuard plugin) { super(plugin, "scaffold"); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        if (!isEnabled()) return;
        Player player = event.getPlayer();
        if (isExempt(player)) return;
        if (plugin.shouldSuppressFlags(player)) return;
        if (player.isFlying() || player.getAllowFlight()) return;

        UUID uuid = player.getUniqueId();
        long now  = System.currentTimeMillis();

        Location pLoc   = player.getLocation();
        Location bLoc   = event.getBlock().getLocation();
        float    pitch  = pLoc.getPitch();

        // --- Sub-check A: high block-place rate ---
        List<Long> times = placeTimes.computeIfAbsent(uuid, k -> new ArrayList<>());
        times.add(now);
        times.removeIf(t -> now - t > 1000);

        int maxRate = plugin.getConfig().getInt("checks.scaffold.max-place-rate", 10);
        if (times.size() > maxRate) {
            flag(player, "place_rate=" + times.size() + "/s");
            times.clear();
            return;
        }

        // --- Sub-check B: placing block directly below feet while looking forward ---
        // "Below feet" means block Y is at or below player's feet Y, and within 1 block XZ
        boolean underFeet = bLoc.getY() <= pLoc.getY()
                && Math.abs(bLoc.getX() - pLoc.getX()) <= 1.0
                && Math.abs(bLoc.getZ() - pLoc.getZ()) <= 1.0;

        // "Looking forward" means not looking down (pitch > -20°)
        // Real scaffold bridging requires pitch < -45° at minimum
        boolean lookingForward = pitch > -20;

        if (underFeet && lookingForward) {
            int streak = aheadStreak.merge(uuid, 1, Integer::sum);
            if (streak >= 4) {
                flag(player, "forward_scaffold pitch=" + String.format("%.1f", pitch) + " streak=" + streak);
                aheadStreak.put(uuid, 0);
            }
        } else {
            aheadStreak.put(uuid, 0);
        }

        // --- Sub-check C: placing multiple blocks while in the air ---
        boolean inAir = !player.isOnGround()
                && !player.isInWater()
                && !player.getLocation().getBlock().getRelative(0, -1, 0).getType().isSolid();

        if (inAir) {
            int streak = airPlaceStreak.merge(uuid, 1, Integer::sum);
            int maxAir = plugin.getConfig().getInt("checks.scaffold.max-air-placements", 3);
            if (streak > maxAir) {
                flag(player, "air_scaffold streak=" + streak);
                airPlaceStreak.put(uuid, 0);
            }
        } else {
            airPlaceStreak.put(uuid, 0);
        }
    }
}
