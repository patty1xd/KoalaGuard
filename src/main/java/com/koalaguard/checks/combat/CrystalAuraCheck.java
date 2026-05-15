package com.koalaguard.checks.combat;

import com.koalaguard.KoalaGuard;
import com.koalaguard.checks.Check;
import org.bukkit.Material;
import org.bukkit.entity.EnderCrystal;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityExplodeEvent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Detects Meteor CrystalAura.
 *
 * From Meteor's source (CrystalAura.java):
 *   - Places an end crystal (BlockPlaceEvent with END_CRYSTAL item) on
 *     obsidian/bedrock adjacent to target.
 *   - Immediately attacks the crystal (EntityDamageByEntityEvent with
 *     EnderCrystal as entity) to detonate it, dealing up to 97 damage.
 *   - Default delay: 0 ticks — place and detonate in the SAME tick.
 *   - Cycle rate: up to 20 place+break cycles per second at 0-tick delay.
 *
 * Server-observable signatures:
 *   A) Crystal placed AND detonated within < 100 ms (same-tick cycle).
 *   B) More than 3 crystal detonations per second (attack rate).
 *   C) Crystal placement is always on obsidian/bedrock within attack range.
 *
 * Detection:
 *   Sub-check A: Track crystal place time. If the crystal explodes AND
 *     the player attacked it within < 100 ms of placement, flag.
 *     Require streak of 3.
 *   Sub-check B: More than 4 EnderCrystal EntityDamageByEntityEvent
 *     hits within 1 second from the same player.
 */
public class CrystalAuraCheck extends Check {

    // UUID of crystal → (placer UUID, place time ms)
    private final Map<UUID, UUID>  crystalPlacer    = new HashMap<>();
    private final Map<UUID, Long>  crystalPlaceMs   = new HashMap<>();

    // Per-player: crystal hit times for rate check
    private final Map<UUID, List<Long>> crystalHitTimes = new HashMap<>();
    // Per-player: rapid cycle streak
    private final Map<UUID, Integer>    cycleStreak     = new HashMap<>();

    private static final long   CYCLE_WINDOW_MS  = 100L;
    private static final int    MAX_HITS_PER_SEC = 4;

    public CrystalAuraCheck(KoalaGuard plugin) { super(plugin, "crystalaura"); }

    // Track crystal placements
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        if (event.getBlockPlaced().getType() != Material.END_CRYSTAL) return;
        Player player = event.getPlayer();
        if (isExempt(player)) return;

        // End crystals placed as items spawn an entity — we track by location
        // Store placer and time keyed by a synthetic UUID from location
        UUID locKey = locationUUID(event.getBlockPlaced().getX(),
                event.getBlockPlaced().getY(), event.getBlockPlaced().getZ());
        crystalPlacer.put(locKey, player.getUniqueId());
        crystalPlaceMs.put(locKey, System.currentTimeMillis());
    }

    // Track crystal hits (player attacks crystal to detonate)
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCrystalHit(EntityDamageByEntityEvent event) {
        if (!isEnabled()) return;
        if (!(event.getEntity() instanceof EnderCrystal crystal)) return;
        if (!(event.getDamager() instanceof Player player)) return;
        if (isExempt(player)) return;
        if (plugin.shouldSuppressFlags(player)) return;

        UUID uuid = player.getUniqueId();
        long now  = System.currentTimeMillis();

        // Sub-check B: hit rate
        List<Long> hits = crystalHitTimes.computeIfAbsent(uuid, k -> new ArrayList<>());
        hits.add(now);
        hits.removeIf(t -> now - t > 1000);
        if (hits.size() > MAX_HITS_PER_SEC) {
            flag(player, "crystal_hit_rate=" + hits.size() + "/s");
            hits.clear();
        }

        // Sub-check A: check if this crystal was just placed by this player
        UUID locKey = locationUUID(
                crystal.getLocation().getBlockX(),
                crystal.getLocation().getBlockY(),
                crystal.getLocation().getBlockZ());
        UUID placer  = crystalPlacer.get(locKey);
        Long placeMs = crystalPlaceMs.get(locKey);

        if (placer != null && placer.equals(uuid) && placeMs != null) {
            long elapsed = now - placeMs;
            if (elapsed < CYCLE_WINDOW_MS) {
                int s = cycleStreak.merge(uuid, 1, Integer::sum);
                if (s >= 3) {
                    flag(player, "crystal_cycle elapsed=" + elapsed + "ms streak=" + s);
                    cycleStreak.put(uuid, 0);
                }
            } else {
                cycleStreak.put(uuid, 0);
            }
            crystalPlacer.remove(locKey);
            crystalPlaceMs.remove(locKey);
        }
    }

    // Clean up crystal tracking on explosion
    @EventHandler(priority = EventPriority.MONITOR)
    public void onExplode(EntityExplodeEvent event) {
        if (!(event.getEntity() instanceof EnderCrystal crystal)) return;
        UUID locKey = locationUUID(
                crystal.getLocation().getBlockX(),
                crystal.getLocation().getBlockY(),
                crystal.getLocation().getBlockZ());
        crystalPlacer.remove(locKey);
        crystalPlaceMs.remove(locKey);
    }

    private static UUID locationUUID(int x, int y, int z) {
        long msb = ((long) x << 32) | (y & 0xFFFFFFFFL);
        return new UUID(msb, z);
    }
}
