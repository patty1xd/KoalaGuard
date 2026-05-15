package com.koalaguard.checks.combat;

import com.koalaguard.KoalaGuard;
import com.koalaguard.checks.Check;
import org.bukkit.Material;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerMoveEvent;

import java.util.*;

/**
 * Detects "NoWeb" / "Web Bypass" cheats.
 *
 * How cobwebs work in vanilla:
 *   Standing inside a cobweb reduces horizontal movement to ~2-5% of normal
 *   (< 0.03 blocks/tick). Players are essentially frozen.
 *
 * What the cheat does:
 *   "NoWeb" or the "WebBypass" variant bypasses the slowdown, letting players
 *   move and attack at full speed while inside cobwebs — commonly used in
 *   crystal PvP to trap opponents in webs while remaining mobile themselves.
 *
 * Sub-checks:
 *   A) Movement speed while inside a cobweb block is above the vanilla cap.
 *   B) Player attacks while inside a cobweb at a CPS rate inconsistent with
 *      being frozen (normally players in webs can barely click due to the
 *      de-facto inability to aim/strafe — we check attack rate vs web exposure).
 */
public class WebAuraCheck extends Check {

    // Vanilla max horizontal speed inside a cobweb (blocks/tick). Generous buffer added.
    private static final double WEB_MAX_SPEED = 0.06;

    // Track consecutive ticks of being inside a cobweb while moving fast
    private final Map<UUID, Integer> webSpeedStreak  = new HashMap<>();

    // Track attack count while inside cobweb
    private final Map<UUID, List<Long>> webAttackTimes = new HashMap<>();

    public WebAuraCheck(KoalaGuard plugin) { super(plugin, "webaura"); }

    // --- Sub-check A: movement speed inside cobweb ---
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (!isEnabled()) return;
        Player player = event.getPlayer();
        if (isExempt(player)) return;
        if (plugin.shouldSuppressFlags(player)) return;
        if (event.getTo() == null) return;
        if (player.isFlying() || player.getAllowFlight() || player.isInsideVehicle()) return;

        // Check if the player's feet are inside a cobweb
        Material blockAtFeet = player.getLocation().getBlock().getType();
        if (blockAtFeet != Material.COBWEB) {
            webSpeedStreak.remove(player.getUniqueId());
            return;
        }

        double dx = event.getTo().getX() - event.getFrom().getX();
        double dz = event.getTo().getZ() - event.getFrom().getZ();
        double horizontal = Math.sqrt(dx * dx + dz * dz);

        UUID uuid = player.getUniqueId();
        if (horizontal > WEB_MAX_SPEED) {
            int streak = webSpeedStreak.merge(uuid, 1, Integer::sum);
            // Require 3 consecutive fast-in-web moves to exclude one-off edge cases
            if (streak >= 3) {
                flag(player, String.format("web_speed=%.4f max=%.4f streak=%d",
                        horizontal, WEB_MAX_SPEED, streak));
                webSpeedStreak.put(uuid, 0);
            }
        } else {
            webSpeedStreak.put(uuid, 0);
        }
    }

    // --- Sub-check B: high attack rate while inside cobweb ---
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onAttackInWeb(EntityDamageByEntityEvent event) {
        if (!isEnabled()) return;
        if (!(event.getDamager() instanceof Player player)) return;
        if (!(event.getEntity() instanceof LivingEntity)) return;
        if (isExempt(player)) return;
        if (plugin.shouldSuppressFlags(player)) return;

        // Only flag if attacker is inside a cobweb
        if (player.getLocation().getBlock().getType() != Material.COBWEB) return;

        UUID uuid = player.getUniqueId();
        long now  = System.currentTimeMillis();

        List<Long> attacks = webAttackTimes.computeIfAbsent(uuid, k -> new ArrayList<>());
        attacks.add(now);
        attacks.removeIf(t -> now - t > 2000); // 2-second window

        // In a real cobweb, attacking 4+ times in 2 seconds is very suspicious
        // because you can barely move. Normally players get 1-2 hits before
        // escaping or being unable to aim.
        int maxWebAttacks = plugin.getConfig().getInt("checks.webaura.max-attacks-in-web", 5);
        if (attacks.size() > maxWebAttacks) {
            flag(player, "web_attacks=" + attacks.size() + " in 2s");
            attacks.clear();
        }
    }
}
