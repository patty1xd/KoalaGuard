package com.koalaguard.checks.movement;

import com.koalaguard.KoalaGuard;
import com.koalaguard.check.MovementCheck;
import com.koalaguard.data.PlayerData;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffectType;

/**
 * Flight / hover detection.
 *
 * A falling player loses Y at an accelerating rate (~ -0.08 per tick after
 * drag). Flight modules cancel that fall, so the player stays level or rises
 * with no support. We count consecutive air ticks where the player resists
 * gravity and only flag after a sustained, unambiguous window.
 */
public final class FlyCheck extends MovementCheck {

    public FlyCheck(KoalaGuard plugin) {
        super(plugin, "fly", "Sustained flight or hovering without support");
    }

    @Override
    public void handle(PlayerData data, Player player) {
        if (data.exemptFlying || data.exemptVehicle || data.exemptGliding || data.exemptLiquid
                || data.exemptRiptide || data.exemptLevitation || data.exemptSlowFalling
                || data.exemptClimbing) { data.setInt(k("air"), 0); return; }

        long now = System.currentTimeMillis();
        if (now - data.slimeBounceMs < 1500 || now - data.bubbleColumnMs < 1500
                || now - data.lastRiptideMs < 2500 || now - data.elytraMs < 2000
                || now - data.lastVelocityMs < 1200 || now - data.lastDamageMs < 900
                || now - data.lastTeleportMs < 2000) { data.setInt(k("air"), 0); return; }

        if (player.hasPotionEffect(PotionEffectType.JUMP_BOOST)) { data.setInt(k("air"), 0); return; }

        if (data.onGround || data.nearGround) {
            data.setInt(k("air"), 0);
            data.subBuffer(k("b"), 5.0);
            return;
        }

        double dy = data.deltaY;
        // Gravity-defying: not falling fast enough to be a natural descent
        if (dy >= -0.062) {
            int air = data.incInt(k("air"));
            // Hovering (≈0) or rising for > 1.2s of continuous air time
            if (air > 24 && dy >= -0.04) {
                double buf = data.addBuffer(k("b"), 3.0, 10.0);
                if (buf >= 6.0) {
                    fail(data, player, String.format("air=%d dy=%.4f", air, dy));
                    data.setBuffer(k("b"), 2.0);
                    data.setInt(k("air"), 12);
                }
            }
        } else {
            data.setInt(k("air"), 0);
            data.subBuffer(k("b"), 1.0);
        }
    }
}
