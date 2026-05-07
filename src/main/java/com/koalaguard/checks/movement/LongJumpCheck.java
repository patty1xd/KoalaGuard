package com.koalaguard.checks.movement;

import com.koalaguard.KoalaGuard;
import com.koalaguard.checks.Check;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.PlayerMoveEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class LongJumpCheck extends Check {
    private final Map<UUID, Double> jumpStartX = new HashMap<>();
    private final Map<UUID, Double> jumpStartZ = new HashMap<>();
    private final Map<UUID, Boolean> wasOnGround = new HashMap<>();

    public LongJumpCheck(KoalaGuard plugin) { super(plugin, "longjump"); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (!isEnabled()) return;
        Player player = event.getPlayer();
        if (player.hasPermission("koalaguard.bypass")) return;
        if (player.getGameMode() == GameMode.CREATIVE || player.getGameMode() == GameMode.SPECTATOR) return;
        if (player.isFlying() || player.isGliding()) return;

        UUID uuid = player.getUniqueId();
        boolean onGround = player.isOnGround();
        boolean prev = wasOnGround.getOrDefault(uuid, true);

        if (prev && !onGround) {
            jumpStartX.put(uuid, event.getFrom().getX());
            jumpStartZ.put(uuid, event.getFrom().getZ());
        } else if (!prev && onGround) {
            if (jumpStartX.containsKey(uuid)) {
                double dx = event.getTo().getX() - jumpStartX.get(uuid);
                double dz = event.getTo().getZ() - jumpStartZ.get(uuid);
                double dist = Math.sqrt(dx * dx + dz * dz);
                if (dist > 6.5) {
                    flag(player, "dist=" + String.format("%.2f", dist));
                }
                jumpStartX.remove(uuid);
                jumpStartZ.remove(uuid);
            }
        }
        wasOnGround.put(uuid, onGround);
    }
}
