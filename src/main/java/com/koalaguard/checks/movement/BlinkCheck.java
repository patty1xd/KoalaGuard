package com.koalaguard.checks.movement;

import com.koalaguard.KoalaGuard;
import com.koalaguard.check.MovementCheck;
import com.koalaguard.data.PlayerData;
import org.bukkit.entity.Player;

/**
 * Blink / FakeLag detection.
 *
 * Blink withholds movement packets while still acting, then releases them in
 * a burst. Server-side this looks like a long silence in position updates
 * (while the player is in combat, so not AFK) followed by a single
 * impossible-magnitude displacement that is not a teleport.
 */
public final class BlinkCheck extends MovementCheck {

    public BlinkCheck(KoalaGuard plugin) {
        super(plugin, "blink", "Withholding then bursting movement packets");
    }

    @Override
    public void handle(PlayerData data, Player player) {
        if (data.exemptVehicle || data.exemptGliding || data.exemptRiptide) {
            data.setLong(k("last"), System.currentTimeMillis());
            return;
        }
        long now = System.currentTimeMillis();
        long last = data.getLong(k("last"));
        data.setLong(k("last"), now);
        if (last == 0) return;

        long gap = now - last;
        if (now - data.lastTeleportMs < 2000 || now - data.lastVelocityMs < 1500) return;

        // Silence ≥ 1.2 s while engaged in combat, then a multi-block jump.
        boolean wasFighting = now - data.lastDamageMs < 4000 || now - data.lastAttackMs < 4000;
        if (gap >= 1200 && wasFighting && data.deltaXZ > 1.5) {
            int s = data.incInt(k("s"));
            if (s >= 2) {
                fail(data, player, String.format("silence=%dms then jump=%.2f streak=%d",
                        gap, data.deltaXZ, s));
                data.setInt(k("s"), 0);
            }
        }
    }
}
