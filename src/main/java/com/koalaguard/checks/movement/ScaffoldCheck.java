package com.koalaguard.checks.movement;

import com.koalaguard.KoalaGuard;
import com.koalaguard.check.CheckCategory;
import com.koalaguard.check.ListenerCheck;
import com.koalaguard.data.PlayerData;
import com.koalaguard.util.LocationUtil;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.util.Vector;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Scaffold — behavioural.
 *
 * A legit bridger (incl. god-bridge / sneak-bridge) ALWAYS looks roughly at
 * the block they are placing, so the angle between their real (packet) look
 * vector and the eye→placed-block vector stays small. Scaffold places blocks
 * under/behind the feet WITHOUT looking there (it fakes the placement's
 * rotation while the flying-packet rotation points elsewhere) → a large,
 * sustained look-vs-place angle. That difference is FP-free for normal
 * bridging. A hard rate guard catches the rest.
 */
public final class ScaffoldCheck extends ListenerCheck {

    public ScaffoldCheck(KoalaGuard plugin) {
        super(plugin, "scaffold", CheckCategory.MOVEMENT, "Automated bridging (placing without looking)");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        if (!isEnabled()) return;
        Player player = event.getPlayer();
        if (isExempt(player) || player.isFlying() || player.getAllowFlight()) return;
        PlayerData d = plugin.getDataManager().get(player);
        if (d == null) return;
        long now = System.currentTimeMillis();
        if (now - d.lastTeleportMs < 1500) return;

        // ── hard rate guard (well above practiced god-bridge) ──
        Deque<Long> times = d.obj(k("t"));
        if (times == null) { times = new ArrayDeque<>(); d.setObj(k("t"), times); }
        times.addLast(now);
        while (!times.isEmpty() && now - times.peekFirst() > 1000) times.removeFirst();
        if (times.size() > cfgI("max-place-rate", 11)) {
            fail(d, player, "place rate=" + times.size() + "/s");
            times.clear();
            return;
        }

        // ── behavioural: do they actually look at what they place? ──
        double ex, ey, ez;
        if (d.pHasPos) { ex = d.pX; ey = d.pY + player.getEyeHeight(); ez = d.pZ; }
        else {
            Location e = player.getEyeLocation();
            ex = e.getX(); ey = e.getY(); ez = e.getZ();
        }
        Location b = event.getBlock().getLocation();
        Vector look = LocationUtil.direction(d.pYaw, d.pPitch);
        Vector to = new Vector(b.getX() + 0.5 - ex, b.getY() + 0.5 - ey, b.getZ() + 0.5 - ez);
        if (to.lengthSquared() < 1e-6) return;
        to.normalize();
        double angle = Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, look.dot(to)))));

        double maxAngle = cfgD("max-angle", 85.0);
        if (angle > maxAngle) {
            double buf = d.addBuffer(k("b"), 1.0 + (angle - maxAngle) / 25.0, 10.0);
            if (buf >= 5.0) {
                fail(d, player, String.format("placed %.0f° off look (real aim)", angle));
                d.setBuffer(k("b"), 1.0);
            }
        } else {
            d.subBuffer(k("b"), 1.5);
        }
    }
}
