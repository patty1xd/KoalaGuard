package com.koalaguard.checks.movement;

import com.koalaguard.KoalaGuard;
import com.koalaguard.check.MovementCheck;
import com.koalaguard.data.PlayerData;
import org.bukkit.Material;
import org.bukkit.entity.Player;

/**
 * NoWeb detection (Meteor "NoSlow"/"Velocity" web bypass).
 *
 * A cobweb pins horizontal speed to ≈0.05 bl/tick. Moving meaningfully
 * faster than that while standing inside a cobweb is the NoWeb signature.
 */
public final class WebCheck extends MovementCheck {

    private static final double WEB_MAX = 0.075;

    public WebCheck(KoalaGuard plugin) {
        super(plugin, "web", "Moving at speed inside a cobweb");
    }

    @Override
    public void handle(PlayerData data, Player player) {
        if (data.exemptFlying || data.exemptVehicle || data.exemptRiptide) {
            data.setInt(k("s"), 0);
            return;
        }
        long now = System.currentTimeMillis();
        if (now - data.lastVelocityMs < 900 || now - data.lastDamageMs < 700) {
            data.setInt(k("s"), 0);
            return;
        }
        Material feet = player.getLocation().getBlock().getType();
        Material legs = player.getLocation().clone().add(0, 0.9, 0).getBlock().getType();
        if (feet != Material.COBWEB && legs != Material.COBWEB) { data.setInt(k("s"), 0); return; }

        if (data.deltaXZ > WEB_MAX) {
            int s = data.incInt(k("s"));
            if (s >= 4) {
                fail(data, player, String.format("web h=%.4f max=%.3f streak=%d",
                        data.deltaXZ, WEB_MAX, s));
                data.setInt(k("s"), 0);
            }
        } else {
            data.setInt(k("s"), 0);
        }
    }
}
