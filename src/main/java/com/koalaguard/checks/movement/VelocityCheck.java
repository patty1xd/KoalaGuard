package com.koalaguard.checks.movement;

import com.koalaguard.KoalaGuard;
import com.koalaguard.checks.Check;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.entity.EntityKnockbackEvent;
import org.bukkit.event.player.PlayerVelocityEvent;
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class VelocityCheck extends Check {
    private final Map<UUID, Vector> expectedVelocity = new HashMap<>();

    public VelocityCheck(KoalaGuard plugin) { super(plugin, "velocity"); }

    @EventHandler
    public void onKnockback(EntityKnockbackEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        expectedVelocity.put(player.getUniqueId(), event.getFinalKnockback().clone());
    }

    @EventHandler
    public void onVelocity(PlayerVelocityEvent event) {
        if (!isEnabled()) return;
        Player player = event.getPlayer();
        if (player.hasPermission("koalaguard.bypass")) return;
        UUID uuid = player.getUniqueId();
        if (!expectedVelocity.containsKey(uuid)) return;

        Vector expected = expectedVelocity.remove(uuid);
        Vector actual = event.getVelocity();
        double ratio = actual.length() / Math.max(expected.length(), 0.001);
        if (ratio < 0.3) {
            flag(player, "ratio=" + String.format("%.2f", ratio));
        }
    }
}
