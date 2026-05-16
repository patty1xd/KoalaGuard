package com.koalaguard.checks.movement;

import com.koalaguard.KoalaGuard;
import com.koalaguard.check.MovementCheck;
import com.koalaguard.data.PlayerData;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

/**
 * Air-strafe check.
 *
 * In vanilla, a player's horizontal velocity direction can only change a
 * little while airborne (very low air control). Strafe / Bhop modules apply
 * fresh directional acceleration every air tick, producing sharp direction
 * changes combined with maintained or increased speed mid-air.
 */
public final class StrafeCheck extends MovementCheck {

    public StrafeCheck(KoalaGuard plugin) {
        super(plugin, "strafe", "Changing direction in mid-air beyond vanilla air control");
    }

    @Override
    public void handle(PlayerData data, Player player) {
        if (data.onGround || data.exemptFlying || data.exemptVehicle || data.exemptGliding
                || data.exemptLiquid || data.exemptRiptide || data.exemptLevitation) {
            data.subBuffer(k("b"), 1.0);
            return;
        }
        long now = System.currentTimeMillis();
        if (now - data.lastVelocityMs < 1000 || now - data.lastDamageMs < 700
                || now - data.slimeBounceMs < 1000) { data.subBuffer(k("b"), 1.0); return; }
        if (data.airTicks < 3) return;                 // need to be settled in the air
        if (data.deltaXZ < 0.18 || data.lastDeltaXZ < 0.18) { data.subBuffer(k("b"), 0.5); return; }

        Vector cur = new Vector(data.deltaX, 0, data.deltaZ);
        Vector prev = new Vector(data.lastDeltaX, 0, data.lastDeltaZ);
        if (cur.lengthSquared() < 1e-6 || prev.lengthSquared() < 1e-6) return;

        double cos = cur.clone().normalize().dot(prev.clone().normalize());
        double angle = Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, cos))));

        // Air direction shift > ~30° while NOT losing speed is not vanilla-possible
        boolean keptSpeed = data.deltaXZ >= data.lastDeltaXZ - 0.02;
        if (angle > 30 && keptSpeed) {
            double buf = data.addBuffer(k("b"), 1.0 + (angle - 30) / 25.0, 9.0);
            if (buf >= 5.0) {
                fail(data, player, String.format("airTurn=%.1f° h=%.3f", angle, data.deltaXZ));
                data.setBuffer(k("b"), 1.0);
            }
        } else {
            data.subBuffer(k("b"), 1.0);
        }
    }
}
