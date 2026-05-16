package com.koalaguard.checks.movement;

import com.koalaguard.KoalaGuard;
import com.koalaguard.check.MovementCheck;
import com.koalaguard.data.PlayerData;
import org.bukkit.entity.Player;

/**
 * Sprint — modern Minecraft allows omnidirectional sprint, so directional
 * heuristics are inherently false-positive-prone and were removed. Only the
 * one genuinely impossible state remains: holding the sprint flag at hunger
 * ≤ 6 (vanilla forcibly stops sprint there). Conservative, long streak,
 * disabled by default in config.
 */
public final class SprintCheck extends MovementCheck {

    public SprintCheck(KoalaGuard plugin) {
        super(plugin, "sprint", "Sprinting at a hunger level vanilla forbids");
    }

    @Override
    public void handle(PlayerData d, Player player) {
        if (!d.sprinting && !player.isSprinting()) { d.setInt(k("s"), 0); return; }
        if (d.exemptVehicle || d.exemptGliding || d.exemptLiquid || d.exemptRiptide) {
            d.setInt(k("s"), 0); return;
        }
        long now = System.currentTimeMillis();
        if (now - d.lastDamageMs < 1000 || now - d.lastVelocityMs < 1000) { d.setInt(k("s"), 0); return; }

        if (player.getFoodLevel() <= 6 && d.deltaXZ > 0.20) {
            int s = d.incInt(k("s"));
            if (s >= 30) {
                fail(d, player, "sprint at hunger " + player.getFoodLevel());
                d.setInt(k("s"), 0);
            }
        } else {
            d.setInt(k("s"), 0);
        }
    }
}
