package com.koalaguard.checks.movement;

import com.koalaguard.KoalaGuard;
import com.koalaguard.check.MovementCheck;
import com.koalaguard.data.PlayerData;
import com.koalaguard.util.MovementPredictor;
import org.bukkit.entity.Player;

/**
 * Motion — the high-confidence vertical physics violations (Vulcan Motion):
 *   1) Jump impulse off the ground larger than vanilla allows (HighJump).
 *   2) Upward re-acceleration while airborne (you cannot gain Y in mid-air
 *      without an external force — Glide/Step-Y/AirJump).
 * Both are unambiguous, so a short buffer + lagback is safe.
 */
public final class MotionCheck extends MovementCheck {

    public MotionCheck(KoalaGuard plugin) {
        super(plugin, "motion", "Vertical motion that violates player physics");
    }

    @Override
    public void handle(PlayerData d, Player player) {
        if (MovementPredictor.verticalUnsafe(d, player)) { d.subBuffer(k("b"), 2.0); return; }

        double dy = d.deltaY, lastDy = d.lastDeltaY;

        // 1) jump impulse too large
        if (d.lastOnGround && !d.onGround && dy > 0) {
            double maxJump = MovementPredictor.maxJump(player);
            if (dy > maxJump) {
                bump(d, player, String.format("jumpY=%.3f max=%.3f", dy, maxJump));
                return;
            }
        }

        // 2) upward acceleration while settled in the air
        if (!d.onGround && d.airTicks > 3 && dy > 0 && dy > lastDy + 0.03 && lastDy <= 0.05) {
            bump(d, player, String.format("airAccelY dy=%.3f lastDy=%.3f air=%d", dy, lastDy, d.airTicks));
            return;
        }
        d.subBuffer(k("b"), 1.0);
    }

    private void bump(PlayerData d, Player p, String detail) {
        double buf = d.addBuffer(k("b"), 3.0, 9.0);
        if (buf >= 6.0) {
            failAndSetback(d, p, detail);
            d.setBuffer(k("b"), 1.0);
        }
    }
}
