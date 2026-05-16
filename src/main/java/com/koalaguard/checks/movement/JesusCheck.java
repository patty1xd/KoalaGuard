package com.koalaguard.checks.movement;

import com.koalaguard.KoalaGuard;
import com.koalaguard.check.MovementCheck;
import com.koalaguard.data.PlayerData;
import org.bukkit.Material;
import org.bukkit.entity.Player;

/**
 * Jesus / water-walk detection.
 *
 * The player moves horizontally across a liquid surface without sinking and
 * while the client reports being on the ground — only possible by spoofing
 * ground packets over water. Frost Walker and lily pads are excluded.
 */
public final class JesusCheck extends MovementCheck {

    public JesusCheck(KoalaGuard plugin) {
        super(plugin, "jesus", "Walking on the surface of a liquid");
    }

    @Override
    public void handle(PlayerData data, Player player) {
        if (data.exemptFlying || data.exemptVehicle || data.exemptRiptide
                || data.exemptSlowFalling) { data.setInt(k("s"), 0); return; }
        long now = System.currentTimeMillis();
        if (now - data.lastRiptideMs < 2500 || now - data.lastVelocityMs < 1000) {
            data.setInt(k("s"), 0);
            return;
        }

        Material feet = player.getLocation().getBlock().getType();
        Material below = player.getLocation().clone().subtract(0, 0.1, 0).getBlock().getType();
        boolean onLiquid = (feet == Material.WATER || feet == Material.LAVA)
                || (below == Material.WATER || below == Material.LAVA);

        if (!onLiquid) { data.setInt(k("s"), 0); data.subBuffer(k("b"), 1.0); return; }
        if (player.isInWater() && data.deltaY < -0.02) { data.setInt(k("s"), 0); return; }

        boolean movingFlat = data.deltaXZ > 0.08 && Math.abs(data.deltaY) < 0.02;
        boolean claimsGround = data.clientGround && !data.serverGround;

        if (movingFlat && claimsGround) {
            int s = data.incInt(k("s"));
            if (s >= 6) {
                double buf = data.addBuffer(k("b"), 3.0, 9.0);
                if (buf >= 5.0) {
                    fail(data, player, String.format("on=%s h=%.3f streak=%d", feet, data.deltaXZ, s));
                    data.setBuffer(k("b"), 1.0);
                }
                data.setInt(k("s"), 0);
            }
        } else {
            data.setInt(k("s"), 0);
        }
    }
}
