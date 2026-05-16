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
 * SelfTrap detection — rapidly sealing a ceiling above the player's head
 * (Y+1..Y+3) within one tick window. Requires repeated bursts.
 */
public final class SelfTrapCheck extends ListenerCheck {

    public SelfTrapCheck(KoalaGuard plugin) {
        super(plugin, "selftrap", CheckCategory.COMBAT, "Auto-trapping with overhead blocks");
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
        if (xz > 1.6 || b.getY() < py + 1.0 || b.getY() > py + 3.0) return;

        long now = System.currentTimeMillis();
        Deque<Long> w = data.obj(k("w"));
        if (w == null) { w = new ArrayDeque<>(); data.setObj(k("w"), w); }
        w.addLast(now);
        while (!w.isEmpty() && now - w.peekFirst() > 140) w.removeFirst();

        if (w.size() >= 3) {
            int s = data.incInt(k("s"));
            if (s >= 2) {
                fail(data, player, "selftrap burst=" + w.size() + " streak=" + s);
                data.setInt(k("s"), 0);
            }
            w.clear();
        }
    }
}
