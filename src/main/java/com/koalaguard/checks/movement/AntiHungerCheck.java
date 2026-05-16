package com.koalaguard.checks.movement;

import com.koalaguard.KoalaGuard;
import com.koalaguard.check.MovementCheck;
import com.koalaguard.data.PlayerData;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffectType;

/**
 * AntiHunger — cancels the START_SPRINTING packet so the server stops
 * counting sprint exhaustion while the player physically sprints. We compare
 * the TRUE packet sprint flag (ENTITY_ACTION) against actual sprint-level
 * ground speed; a long sustained desync is the signature.
 */
public final class AntiHungerCheck extends MovementCheck {

    private static final double SPRINT_SPEED = 0.255;

    public AntiHungerCheck(KoalaGuard plugin) {
        super(plugin, "antihunger", "Suppressing the sprint packet while sprinting");
    }

    @Override
    public void handle(PlayerData d, Player player) {
        if (d.exemptFlying || d.exemptVehicle || d.exemptGliding || d.exemptLiquid
                || d.exemptRiptide || !d.onGround) { d.setInt(k("s"), 0); return; }
        if (player.hasPotionEffect(PotionEffectType.SPEED)) { d.setInt(k("s"), 0); return; }
        long now = System.currentTimeMillis();
        if (now - d.lastDamageMs < 900 || now - d.lastVelocityMs < 1000) { d.setInt(k("s"), 0); return; }

        if (d.deltaXZ >= SPRINT_SPEED && !d.sprinting && !d.sneaking) {
            int s = d.incInt(k("s"));
            if (s >= 30) {
                fail(d, player, String.format("speed=%.3f sprintPacket=false streak=%d", d.deltaXZ, s));
                d.setInt(k("s"), 0);
            }
        } else {
            d.setInt(k("s"), 0);
        }
    }
}
