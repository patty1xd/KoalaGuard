package com.koalaguard.checks.movement;

import com.koalaguard.KoalaGuard;
import com.koalaguard.check.MovementCheck;
import com.koalaguard.data.PlayerData;
import com.koalaguard.util.MovementPredictor;
import org.bukkit.entity.Player;

/**
 * Speed — behavioural. Instead of a fixed cap, it replays vanilla horizontal
 * physics from the player's OWN previous speed (friction + acceleration via
 * {@link MovementPredictor}) and flags only a SUSTAINED prediction error.
 * Knockback / ice / sprint-jump are part of the model, so they don't FP; a
 * Speed/Bhop/Strafe module exceeds the converged vanilla maximum tick after
 * tick and the decaying buffer catches it.
 */
public final class SpeedCheck extends MovementCheck {

    public SpeedCheck(KoalaGuard plugin) {
        super(plugin, "speed", "Horizontal speed exceeds replicated vanilla physics");
    }

    @Override
    public void handle(PlayerData d, Player player) {
        if (d.exemptFlying || d.exemptVehicle || d.exemptGliding
                || d.exemptLiquid || d.exemptRiptide || d.exemptLevitation) {
            d.subBuffer(k("b"), 1.0); return;
        }
        long now = System.currentTimeMillis();
        if (now - d.slimeBounceMs < 1000 || now - d.bubbleColumnMs < 1000
                || now - d.lastRiptideMs < 1800 || now - d.elytraMs < 1200
                || now - d.lastVelocityMs < 1000 || now - d.lastDamageMs < 800
                || now - d.lastTeleportMs < 1500) { d.subBuffer(k("b"), 1.0); return; }
        if (!d.positionChanged) return;

        double predicted = MovementPredictor.predictMaxHorizontal(d, player);

        // Lag tolerance: a small base epsilon + a slice of measured latency
        // (transaction RTT) so a laggy honest player is never punished.
        int tping = d.transactionPing > 0 ? d.transactionPing : plugin.getMetrics().pingMs(player);
        double epsilon = 0.055 + (tping > 0 ? Math.min(0.05, tping / 4000.0) : 0);
        double allowed = predicted + epsilon;

        if (d.deltaXZ > allowed) {
            double over = d.deltaXZ - allowed;
            double buf = d.addBuffer(k("b"), 1.0 + over * 8.0, 14.0);
            if (buf >= 6.0) {
                failAndSetback(d, player, String.format("h=%.3f predicted=%.3f over=%.3f",
                        d.deltaXZ, predicted, over));
                d.setBuffer(k("b"), 1.5);
            }
        } else {
            d.subBuffer(k("b"), 1.0);
        }
    }
}
