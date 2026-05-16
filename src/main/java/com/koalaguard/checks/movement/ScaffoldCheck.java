package com.koalaguard.checks.movement;

import com.koalaguard.KoalaGuard;
import com.koalaguard.check.CheckCategory;
import com.koalaguard.check.ListenerCheck;
import com.koalaguard.data.PlayerData;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.BlockPlaceEvent;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Scaffold / auto-bridge detection.
 *
 * Real bridging requires a downward pitch and tops out around 6 blocks/sec.
 * Scaffold places blocks under the feet at a high rate while looking forward
 * (pitch not steeply down), often mid-air. Several weak signals feed one
 * decaying buffer.
 */
public final class ScaffoldCheck extends ListenerCheck {

    public ScaffoldCheck(KoalaGuard plugin) {
        super(plugin, "scaffold", CheckCategory.MOVEMENT, "Automated bridging / scaffold");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        if (!isEnabled()) return;
        Player player = event.getPlayer();
        if (isExempt(player) || player.isFlying() || player.getAllowFlight()) return;
        PlayerData data = plugin.getDataManager().get(player);
        if (data == null) return;

        long now = System.currentTimeMillis();
        Deque<Long> times = data.obj(k("t"));
        if (times == null) { times = new ArrayDeque<>(); data.setObj(k("t"), times); }
        times.addLast(now);
        while (!times.isEmpty() && now - times.peekFirst() > 1000) times.removeFirst();

        Location p = player.getLocation();
        Location b = event.getBlock().getLocation();
        float pitch = p.getPitch();

        double evidence = 0;
        int rate = times.size();
        if (rate > cfgI("max-place-rate", 8)) evidence += 2.5;

        boolean underFeet = b.getY() < p.getY() && Math.abs(b.getX() + 0.5 - p.getX()) <= 1.0
                && Math.abs(b.getZ() + 0.5 - p.getZ()) <= 1.0;
        boolean lookingForward = pitch > -22;
        if (underFeet && lookingForward) evidence += 2.0;

        boolean inAir = !player.isOnGround()
                && !player.getLocation().clone().subtract(0, 0.1, 0).getBlock().getType().isSolid();
        if (inAir && underFeet) evidence += 1.5;

        if (evidence > 0) {
            double buf = data.addBuffer(k("b"), evidence, 12.0);
            if (buf >= 6.0) {
                fail(data, player, String.format("rate=%d/s pitch=%.0f° air=%b",
                        rate, pitch, inAir));
                data.setBuffer(k("b"), 1.0);
            }
        } else {
            data.subBuffer(k("b"), 1.5);
        }
    }
}
