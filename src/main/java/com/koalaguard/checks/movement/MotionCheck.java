package com.koalaguard.checks.movement;

import com.koalaguard.KoalaGuard;
import com.koalaguard.check.MovementCheck;
import com.koalaguard.data.PlayerData;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffectType;

/**
 * Invalid vertical motion.
 *
 * Catches the high-confidence physics violations: a jump impulse larger than
 * vanilla allows, and upward acceleration while already airborne (you cannot
 * gain Y in mid-air without an external force). These are the signatures of
 * Fly/Glide/HighJump/Step-Y modules that purely event-speed checks miss.
 */
public final class MotionCheck extends MovementCheck {

    public MotionCheck(KoalaGuard plugin) {
        super(plugin, "motion", "Vertical motion that violates player physics");
    }

    @Override
    public void handle(PlayerData data, Player player) {
        if (data.exemptFlying || data.exemptVehicle || data.exemptGliding || data.exemptLiquid
                || data.exemptRiptide || data.exemptLevitation || data.exemptSlowFalling
                || data.exemptClimbing) { data.subBuffer(k("b"), 1.0); return; }

        long now = System.currentTimeMillis();
        if (now - data.lastVelocityMs < 1200 || now - data.lastDamageMs < 800
                || now - data.slimeBounceMs < 1500 || now - data.bubbleColumnMs < 1200
                || now - data.lastRiptideMs < 2000 || now - data.elytraMs < 1500
                || now - data.lastTeleportMs < 1500) { data.subBuffer(k("b"), 1.0); return; }

        double dy = data.deltaY;
        double lastDy = data.lastDeltaY;

        // 1) Jump impulse too large (just left the ground this tick)
        if (data.lastOnGround && !data.onGround && dy > 0) {
            double maxJump = 0.42;
            if (player.hasPotionEffect(PotionEffectType.JUMP_BOOST)) {
                int amp = player.getPotionEffect(PotionEffectType.JUMP_BOOST).getAmplifier();
                maxJump += 0.1 * (amp + 1);
            }
            maxJump += 0.06; // slab/scaffold edge tolerance
            if (dy > maxJump) {
                bump(data, player, String.format("jumpY=%.3f max=%.3f", dy, maxJump));
                return;
            }
        }

        // 2) Upward acceleration while settled in the air (impossible)
        if (!data.onGround && data.airTicks > 3 && dy > 0 && dy > lastDy + 0.02 && lastDy <= 0.05) {
            bump(data, player, String.format("airAccelY dy=%.3f lastDy=%.3f air=%d",
                    dy, lastDy, data.airTicks));
            return;
        }

        data.subBuffer(k("b"), 0.5);
    }

    private void bump(PlayerData data, Player player, String detail) {
        double buf = data.addBuffer(k("b"), 2.5, 8.0);
        if (buf >= 5.0) {
            fail(data, player, detail);
            data.setBuffer(k("b"), 1.0);
        }
    }
}
