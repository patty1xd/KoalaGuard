package com.koalaguard.checks.movement;

import com.koalaguard.KoalaGuard;
import com.koalaguard.check.MovementCheck;
import com.koalaguard.data.PlayerData;
import com.koalaguard.util.MovementPredictor;
import org.bukkit.entity.Player;

/**
 * Fly — vertical-prediction offset + lagback.
 *
 * Targets the real cheat behaviour (LiquidBounce Fly Vanilla/Motion/Verus/
 * Jetpack, FDP/Rise hover): after the jump arc would have ended, a falling
 * player's Y velocity must keep decaying toward terminal. Flight cancels
 * that, so the player hovers/rises. We only evaluate once a legitimate jump
 * would already be descending (airTicks > 10) — so jumps never FP — and
 * compare actual vs the replicated gravity curve. Egregious → setback.
 */
public final class FlyCheck extends MovementCheck {

    public FlyCheck(KoalaGuard plugin) {
        super(plugin, "fly", "Resisting gravity (flight/hover)");
    }

    @Override
    public void handle(PlayerData d, Player player) {
        if (MovementPredictor.verticalUnsafe(d, player)
                || player.hasPotionEffect(org.bukkit.potion.PotionEffectType.JUMP_BOOST)) {
            d.setInt(k("air"), 0); d.subBuffer(k("b"), 3.0); return;
        }
        if (d.onGround || d.nearGround) { d.setInt(k("air"), 0); d.subBuffer(k("b"), 4.0); return; }

        int air = d.incInt(k("air"));
        if (air <= 10) return;                       // still inside a possible jump arc

        double predicted = MovementPredictor.expectedDeltaY(d, player); // strongly negative by now
        double offset = d.deltaY - predicted;        // >0 ⇒ resisting gravity

        if (offset > 0.04 && d.deltaY >= -0.06) {
            double buf = d.addBuffer(k("b"), 1.0 + offset * 10.0, 12.0);
            if (buf >= 7.0) {
                failAndSetback(d, player, String.format("dy=%.3f predicted=%.3f offset=%.3f air=%d",
                        d.deltaY, predicted, offset, air));
                d.setBuffer(k("b"), 2.0);
                d.setInt(k("air"), 0);
            }
        } else {
            d.subBuffer(k("b"), 1.5);
        }
    }
}
