package com.koalaguard.checks.movement;

import com.koalaguard.KoalaGuard;
import com.koalaguard.check.MovementCheck;
import com.koalaguard.data.PlayerData;
import org.bukkit.entity.Player;

/**
 * ElytraFly detection.
 *
 * Real elytra flight always trades altitude for speed unless boosted by a
 * firework (which produces a server velocity event). Sustained level/rising
 * gliding while horizontal speed does NOT decay, with no recent velocity
 * impulse, is only possible with an ElytraFly module.
 */
public final class ElytraFlyCheck extends MovementCheck {

    public ElytraFlyCheck(KoalaGuard plugin) {
        super(plugin, "elytrafly", "Powered/level elytra flight without boosts");
    }

    @Override
    public void handle(PlayerData data, Player player) {
        if (!player.isGliding()) { data.setInt(k("s"), 0); return; }
        if (data.exemptVehicle || data.exemptRiptide) { data.setInt(k("s"), 0); return; }
        long now = System.currentTimeMillis();
        // Firework / knockback boosts fire a velocity event — give them room.
        if (now - data.lastVelocityMs < 2500 || now - data.lastDamageMs < 1500
                || now - data.lastTeleportMs < 2000) { data.setInt(k("s"), 0); return; }

        boolean rising = data.deltaY >= -0.02;            // not descending
        boolean keepsSpeed = data.deltaXZ >= data.lastDeltaXZ - 0.02 && data.deltaXZ > 0.35;

        if (rising && keepsSpeed) {
            int s = data.incInt(k("s"));
            if (s >= 30) {
                fail(data, player, String.format("level glide dy=%.3f h=%.3f streak=%d",
                        data.deltaY, data.deltaXZ, s));
                data.setInt(k("s"), 0);
            }
        } else {
            data.addInt(k("s"), -2);
        }
    }
}
