package com.koalaguard.checks.combat;

import com.koalaguard.KoalaGuard;
import com.koalaguard.check.CheckCategory;
import com.koalaguard.check.ListenerCheck;
import com.koalaguard.data.PlayerData;
import org.bukkit.Material;
import org.bukkit.entity.EnderCrystal;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityExplodeEvent;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * CrystalAura detection.
 *
 * Flags two impossibilities: a crystal placed and detonated by the same
 * player within ~120 ms (same-tick cycle) and a sustained crystal-break rate
 * far above human click speed.
 */
public final class CrystalAuraCheck extends ListenerCheck {

    // location-key -> place time; bounded, cleaned on explode/hit
    private final Map<Long, long[]> placed = new ConcurrentHashMap<>();

    public CrystalAuraCheck(KoalaGuard plugin) {
        super(plugin, "crystalaura", CheckCategory.COMBAT, "Automated crystal place + detonate");
    }

    private long key(int x, int y, int z) {
        return (((long) x & 0x3FFFFFF) << 38) | (((long) y & 0xFFF) << 26) | ((long) z & 0x3FFFFFF);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        if (event.getBlockPlaced().getType() != Material.END_CRYSTAL) return;
        if (placed.size() > 4096) placed.clear();
        placed.put(key(event.getBlockPlaced().getX(), event.getBlockPlaced().getY(),
                        event.getBlockPlaced().getZ()),
                new long[]{System.currentTimeMillis(),
                        event.getPlayer().getUniqueId().getLeastSignificantBits()});
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHit(EntityDamageByEntityEvent event) {
        if (!isEnabled()) return;
        if (!(event.getEntity() instanceof EnderCrystal crystal)) return;
        if (!(event.getDamager() instanceof Player player)) return;
        if (isExempt(player)) return;
        PlayerData data = plugin.getDataManager().get(player);
        if (data == null) return;

        long now = System.currentTimeMillis();
        Deque<Long> hits = data.obj(k("h"));
        if (hits == null) { hits = new ArrayDeque<>(); data.setObj(k("h"), hits); }
        hits.addLast(now);
        while (!hits.isEmpty() && now - hits.peekFirst() > 1000) hits.removeFirst();
        if (hits.size() > cfgI("max-hits-per-sec", 6)) {
            fail(data, player, "crystal break rate=" + hits.size() + "/s");
            hits.clear();
        }

        long kk = key(crystal.getLocation().getBlockX(), crystal.getLocation().getBlockY(),
                crystal.getLocation().getBlockZ());
        long[] meta = placed.remove(kk);
        if (meta != null && meta[1] == player.getUniqueId().getLeastSignificantBits()) {
            long elapsed = now - meta[0];
            if (elapsed < 120) {
                int s = data.incInt(k("s"));
                if (s >= 3) {
                    fail(data, player, "place→detonate " + elapsed + "ms streak=" + s);
                    data.setInt(k("s"), 0);
                }
            } else {
                data.setInt(k("s"), 0);
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onExplode(EntityExplodeEvent event) {
        if (!(event.getEntity() instanceof EnderCrystal c)) return;
        placed.remove(key(c.getLocation().getBlockX(), c.getLocation().getBlockY(),
                c.getLocation().getBlockZ()));
    }
}
