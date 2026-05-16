package com.koalaguard.checks.movement;

import com.koalaguard.KoalaGuard;
import com.koalaguard.check.MovementCheck;
import com.koalaguard.data.PlayerData;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffectType;

/**
 * Step detection.
 *
 * Vanilla step height is 0.6 (auto-stepping slabs/stairs). Step modules raise
 * it to 1.0+ so the player teleports up full blocks without a jump arc. A
 * jump's first tick is ≈ +0.42 and is preceded by a near-zero ground tick, so
 * a single >0.6 Y gain that begins from the ground (no upward velocity last
 * tick) and is not a jump is the Step signature.
 */
public final class StepCheck extends MovementCheck {

    private static final double VANILLA_STEP = 0.6;

    public StepCheck(KoalaGuard plugin) {
        super(plugin, "step", "Stepping up full blocks without jumping");
    }

    @Override
    public void handle(PlayerData data, Player player) {
        if (data.exemptFlying || data.exemptVehicle || data.exemptGliding || data.exemptLiquid
                || data.exemptRiptide || data.exemptClimbing) { data.subBuffer(k("b"), 1.0); return; }
        if (player.hasPotionEffect(PotionEffectType.JUMP_BOOST)) { data.subBuffer(k("b"), 1.0); return; }

        long now = System.currentTimeMillis();
        if (now - data.slimeBounceMs < 1200 || now - data.bubbleColumnMs < 1200
                || now - data.lastVelocityMs < 900 || now - data.lastTeleportMs < 1200) {
            data.subBuffer(k("b"), 1.0);
            return;
        }

        double dy = data.deltaY;
        if (dy <= VANILLA_STEP + 0.05) { data.subBuffer(k("b"), 0.5); return; }

        // A jump's previous tick has notable upward velocity; a step does not.
        boolean wasJumpArc = data.lastDeltaY >= 0.30;
        boolean fromGround = data.lastOnGround || data.groundTicks <= 1;

        if (!wasJumpArc && fromGround && data.onGround) {
            double buf = data.addBuffer(k("b"), 2.0, 8.0);
            if (buf >= 4.0) {
                fail(data, player, String.format("stepY=%.3f vanillaMax=%.1f", dy, VANILLA_STEP));
                data.setBuffer(k("b"), 1.0);
            }
        } else {
            data.subBuffer(k("b"), 1.0);
        }
    }
}
