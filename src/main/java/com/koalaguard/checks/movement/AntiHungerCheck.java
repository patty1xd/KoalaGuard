package com.koalaguard.checks.movement;

import com.koalaguard.KoalaGuard;
import com.koalaguard.check.MovementCheck;
import com.koalaguard.data.PlayerData;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffectType;

/**
 * AntiHunger detection.
 *
 * AntiHunger cancels the START_SPRINTING packet so the server stops counting
 * sprint exhaustion while the player still physically sprints. The result is
 * a measurable desync: sprint-level horizontal speed with the sprint flag
 * reported false, sustained for ~1 second on the ground.
 */
public final class AntiHungerCheck extends MovementCheck {

    private static final double SPRINT_SPEED = 0.255;

    public AntiHungerCheck(KoalaGuard plugin) {
        super(plugin, "antihunger", "Sprinting with the sprint flag suppressed");
    }

    @Override
    public void handle(PlayerData data, Player player) {
        if (data.exemptFlying || data.exemptVehicle || data.exemptGliding || data.exemptLiquid
                || data.exemptRiptide) { data.setInt(k("s"), 0); return; }
        if (player.hasPotionEffect(PotionEffectType.SPEED)) { data.setInt(k("s"), 0); return; }
        long now = System.currentTimeMillis();
        if (now - data.lastDamageMs < 800 || now - data.lastVelocityMs < 900) {
            data.setInt(k("s"), 0);
            return;
        }
        if (!data.onGround) { data.setInt(k("s"), 0); return; }

        if (data.deltaXZ >= SPRINT_SPEED && !player.isSprinting() && !player.isSneaking()) {
            int s = data.incInt(k("s"));
            if (s >= 24) {
                fail(data, player, String.format("speed=%.3f sprintFlag=false streak=%d",
                        data.deltaXZ, s));
                data.setInt(k("s"), 0);
            }
        } else {
            data.setInt(k("s"), 0);
        }
    }
}
