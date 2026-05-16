package com.koalaguard.checks.combat;

import com.koalaguard.KoalaGuard;
import com.koalaguard.check.CheckCategory;
import com.koalaguard.check.ListenerCheck;
import com.koalaguard.data.PlayerData;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.BlockPlaceEvent;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Surround detection — 4 blocks placed around the player's feet within a
 * single tick window (crystal-PvP self-protection). Requires consecutive
 * bursts so manual quick-walling never flags.
 */
public final class SurroundCheck extends ListenerCheck {

    public SurroundCheck(KoalaGuard plugin) {
        super(plugin, "surround", CheckCategory.COMBAT, "Auto-surrounding feet with blocks");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        if (!isEnabled()) return;
        Player player = event.getPlayer();
        if (isExempt(player)) return;
        PlayerData data = plugin.getDataManager().get(player);
        if (data == null) return;

        Block b = event.getBlock();
        double px = player.getLocation().getX();
        double py = player.getLocation().getY();
        double pz = player.getLocation().getZ();
        double xz = Math.hypot(px - (b.getX() + 0.5), pz - (b.getZ() + 0.5));
        if (xz > 1.6 || b.getY() < py - 1.6 || b.getY() > py + 1.0) return;

        long now = System.currentTimeMillis();
        Deque<Long> w = data.obj(k("w"));
        if (w == null) { w = new ArrayDeque<>(); data.setObj(k("w"), w); }
        w.addLast(now);
        while (!w.isEmpty() && now - w.peekFirst() > 120) w.removeFirst();

        if (w.size() >= 4) {
            int s = data.incInt(k("s"));
            if (s >= 2) {
                fail(data, player, "surround burst=" + w.size() + " streak=" + s);
                data.setInt(k("s"), 0);
            }
            w.clear();
        }
    }
}
