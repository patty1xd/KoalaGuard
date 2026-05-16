package com.koalaguard.checks.movement;

import com.koalaguard.KoalaGuard;
import com.koalaguard.check.MovementCheck;
import com.koalaguard.data.PlayerData;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffectType;

/**
 * NoSlow detection.
 *
 * Eating, drinking, charging a bow/trident or raising a shield applies a
 * 0.2× movement multiplier server-side. NoSlow cancels it, so the player
 * keeps near-sprint speed while their hand is raised.
 */
public final class NoSlowCheck extends MovementCheck {

    private static final double USING_MAX = 0.115; // ~ sprint × 0.2 + tolerance

    public NoSlowCheck(KoalaGuard plugin) {
        super(plugin, "noslow", "Moving at full speed while using an item");
    }

    @Override
    public void handle(PlayerData data, Player player) {
        if (data.exemptFlying || data.exemptVehicle || data.exemptLiquid
                || data.exemptRiptide) { data.subBuffer(k("b"), 1.0); return; }
        if (player.hasPotionEffect(PotionEffectType.SPEED)) { data.subBuffer(k("b"), 1.0); return; }
        long now = System.currentTimeMillis();
        if (now - data.lastVelocityMs < 900 || now - data.lastDamageMs < 700) {
            data.subBuffer(k("b"), 1.0);
            return;
        }

        boolean raised;
        ItemStack active;
        try {
            raised = player.isHandRaised();
            active = player.getActiveItem();
        } catch (Throwable t) {
            return;
        }
        if (!raised || active == null || !isSlowItem(active.getType())) {
            data.setLong(k("since"), 0);
            data.subBuffer(k("b"), 1.0);
            return;
        }

        long since = data.getLong(k("since"));
        if (since == 0) { data.setLong(k("since"), now); return; }
        if (now - since < 250) return;                 // let the server apply the slowdown

        if (data.deltaXZ > USING_MAX) {
            double buf = data.addBuffer(k("b"), 1.5, 9.0);
            if (buf >= 5.0) {
                fail(data, player, String.format("item=%s h=%.3f max=%.3f",
                        active.getType(), data.deltaXZ, USING_MAX));
                data.setBuffer(k("b"), 1.0);
            }
        } else {
            data.subBuffer(k("b"), 1.0);
        }
    }

    private boolean isSlowItem(Material m) {
        if (m.isEdible()) return true;
        String n = m.name();
        return n.contains("POTION") || m == Material.BOW || m == Material.CROSSBOW
                || m == Material.SHIELD || m == Material.TRIDENT
                || m == Material.SPYGLASS || m == Material.GOAT_HORN;
    }
}
