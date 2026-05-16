package com.koalaguard.checks.movement;

import com.koalaguard.KoalaGuard;
import com.koalaguard.check.MovementCheck;
import com.koalaguard.data.PlayerData;
import org.bukkit.entity.Player;

/**
 * NoFall detection.
 *
 * NoFall spoofs an onGround=true packet mid-air so the server zeroes the
 * accumulated fall distance and applies no damage. We track the real fall
 * (server-side Y descent) and flag when the client claims ground / fall
 * distance resets while the player is demonstrably still in the air with no
 * solid block beneath them.
 */
public final class NoFallCheck extends MovementCheck {

    public NoFallCheck(KoalaGuard plugin) {
        super(plugin, "nofall", "Spoofing ground state to negate fall damage");
    }

    @Override
    public void handle(PlayerData data, Player player) {
        if (data.exemptFlying || data.exemptVehicle || data.exemptGliding || data.exemptLiquid
                || data.exemptRiptide || data.exemptSlowFalling || data.exemptClimbing
                || data.exemptLevitation) { data.setBuffer(k("fall"), 0); return; }

        long now = System.currentTimeMillis();
        if (now - data.lastVelocityMs < 1200 || now - data.slimeBounceMs < 1500
                || now - data.bubbleColumnMs < 1200 || now - data.lastTeleportMs < 1500) {
            data.setBuffer(k("fall"), 0);
            return;
        }

        double fallen = data.buffer(k("fall"));

        if (data.deltaY < -0.08 && !data.onGround && !data.serverGround) {
            // genuinely descending in open air — accumulate
            data.addBuffer(k("fall"), -data.deltaY, 64.0);
            return;
        }

        boolean reallyGrounded = data.serverGround;
        if ((data.clientGround || player.getFallDistance() < 0.4) && !reallyGrounded
                && data.deltaY < -0.08 && fallen >= 3.2) {
            double buf = data.addBuffer(k("b"), 3.0, 9.0);
            if (buf >= 5.0) {
                fail(data, player, String.format("fall=%.1f blocks, claimed ground in air", fallen));
                data.setBuffer(k("b"), 1.0);
            }
            data.setBuffer(k("fall"), 0);
            return;
        }

        if (reallyGrounded) {
            data.setBuffer(k("fall"), 0);
            data.subBuffer(k("b"), 1.0);
        }
    }
}
