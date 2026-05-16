package com.koalaguard.checks.movement;

import com.koalaguard.KoalaGuard;
import com.koalaguard.check.MovementCheck;
import com.koalaguard.data.PlayerData;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

/**
 * Illegal sprint states (OmniSprint / Sprint).
 *
 * Vanilla only sprints forward and not while sneaking or at hunger ≤ 6.
 * Sprint modules keep the sprint flag on while moving backwards/sideways or
 * while sneaking, which is mechanically impossible.
 */
public final class SprintCheck extends MovementCheck {

    public SprintCheck(KoalaGuard plugin) {
        super(plugin, "sprint", "Sprinting in a state vanilla forbids");
    }

    @Override
    public void handle(PlayerData data, Player player) {
        if (!player.isSprinting()) { data.subBuffer(k("b"), 1.0); return; }
        if (data.exemptFlying || data.exemptVehicle || data.exemptGliding || data.exemptLiquid
                || data.exemptRiptide) { data.subBuffer(k("b"), 1.0); return; }
        long now = System.currentTimeMillis();
        if (now - data.lastDamageMs < 800 || now - data.lastVelocityMs < 900) {
            data.subBuffer(k("b"), 1.0);
            return;
        }

        String reason = null;
        if (player.getFoodLevel() <= 6) reason = "sprint at hunger " + player.getFoodLevel();
        else if (player.isSneaking()) reason = "sprint while sneaking";
        else if (data.deltaXZ > 0.08) {
            Vector look = player.getLocation().getDirection();
            look.setY(0);
            Vector move = new Vector(data.deltaX, 0, data.deltaZ);
            if (look.lengthSquared() > 1e-6 && move.lengthSquared() > 1e-6) {
                double dot = look.normalize().dot(move.normalize());
                if (dot < -0.35) reason = String.format("backward sprint dot=%.2f", dot);
            }
        }

        if (reason != null) {
            double buf = data.addBuffer(k("b"), 1.0, 10.0);
            if (buf >= 7.0) {
                fail(data, player, reason);
                data.setBuffer(k("b"), 2.0);
            }
        } else {
            data.subBuffer(k("b"), 1.0);
        }
    }
}
