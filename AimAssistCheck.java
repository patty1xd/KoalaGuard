package com.koalaguard.checks.combat;

import com.koalaguard.KoalaGuard;
import com.koalaguard.checks.Check;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerMoveEvent;

import java.util.*;

public class AimAssistCheck extends Check {

    private final Map<UUID, List<Float>> pitchSamples = new HashMap<>();
    private final Map<UUID, Boolean> recentlyHit = new HashMap<>();

    public AimAssistCheck(KoalaGuard plugin) { super(plugin, "aimassist"); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (!isEnabled()) return;
        Player player = event.getPlayer();
        if (player.hasPermission("koalaguard.bypass")) return;
        if (!recentlyHit.getOrDefault(player.getUniqueId(), false)) return;

        // Track pitch changes around combat - aim assist snaps pitch toward target
        List<Float> samples = pitchSamples.computeIfAbsent(player.getUniqueId(), k -> new ArrayList<>());
        float pitchDelta = Math.abs(event.getTo().getPitch() - event.getFrom().getPitch());
        samples.add(pitchDelta);
        if (samples.size() > 30) samples.remove(0);

        if (samples.size() == 30) {
            // Aim assist produces unnaturally smooth, consistent pitch corrections
            double variance = computeVariance(samples);
            double mean = samples.stream().mapToDouble(Float::doubleValue).average().orElse(0);
            // Real players: high variance. Aim assist: low variance with nonzero mean
            if (variance < 1.0 && mean > 0.5 && mean < 5.0) {
                flag(player, "pitch_variance=" + String.format("%.3f", variance) + " mean=" + String.format("%.2f", mean));
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player player)) return;
        recentlyHit.put(player.getUniqueId(), true);
        // Clear after 2 seconds
        plugin.getServer().getScheduler().runTaskLater(plugin,
                () -> recentlyHit.remove(player.getUniqueId()), 40L);
    }

    private double computeVariance(List<Float> values) {
        double mean = values.stream().mapToDouble(Float::doubleValue).average().orElse(0);
        return values.stream().mapToDouble(v -> Math.pow(v - mean, 2)).average().orElse(0);
    }
}
