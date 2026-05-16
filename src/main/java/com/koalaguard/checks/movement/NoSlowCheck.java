package com.koalaguard.checks.movement;

import com.koalaguard.KoalaGuard;
import com.koalaguard.check.MovementCheck;
import com.koalaguard.data.PlayerData;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffectType;

/**
 * NoSlow — uses the EXACT packet item-use window (USE_ITEM →
 * RELEASE_USE_ITEM) instead of guessing from Bukkit timing. While the hand
 * is genuinely raised on a slowing item, on the ground, with no speed
 * potion / ice, sustained near-sprint speed is impossible.
 */
public final class NoSlowCheck extends MovementCheck {

    private static final double USING_MAX = 0.117;

    public NoSlowCheck(KoalaGuard plugin) {
        super(plugin, "noslow", "Moving at full speed while using an item");
    }

    @Override
    public void handle(PlayerData d, Player player) {
        if (d.exemptFlying || d.exemptVehicle || d.exemptLiquid || d.exemptRiptide
                || !d.onGround) { d.subBuffer(k("b"), 1.0); return; }
        if (player.hasPotionEffect(PotionEffectType.SPEED)) { d.subBuffer(k("b"), 1.0); return; }
        long now = System.currentTimeMillis();
        if (now - d.lastVelocityMs < 1000 || now - d.lastDamageMs < 800
                || now - d.slimeBounceMs < 1000) { d.subBuffer(k("b"), 1.0); return; }

        Material below = player.getLocation().clone().subtract(0, 0.3, 0).getBlock().getType();
        if (below == Material.ICE || below == Material.PACKED_ICE || below == Material.BLUE_ICE
                || below == Material.SLIME_BLOCK) { d.subBuffer(k("b"), 1.0); return; }

        if (!d.usingItem || now - d.useStartMs < 350) { d.subBuffer(k("b"), 0.5); return; }

        if (d.deltaXZ > USING_MAX) {
            double buf = d.addBuffer(k("b"), 1.5, 9.0);
            if (buf >= 5.0) {
                fail(d, player, String.format("h=%.3f max=%.3f while using item", d.deltaXZ, USING_MAX));
                d.setBuffer(k("b"), 1.0);
            }
        } else {
            d.subBuffer(k("b"), 1.0);
        }
    }
}
