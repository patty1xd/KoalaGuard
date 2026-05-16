package com.koalaguard.util;

import com.koalaguard.data.PlayerData;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffectType;

/**
 * Server-side vanilla horizontal-physics replication.
 *
 * Minecraft integrates horizontal velocity as:
 *     v_next = v * frictionFactor + acceleration
 * which converges to a max of accel / (1 - frictionFactor). Using the
 * player's OWN previous speed as the input means knockback, ice momentum and
 * sprint-jumps are all modelled instead of hard-coded — this is behavioural
 * detection, not a magic threshold, so it stays FP-free while staying tight.
 *
 *   ground (slip 0.6): 0.13/(1-0.91*0.6)  ≈ 0.286  (vanilla sprint ✓)
 *   walk:              0.10/(1-0.546)     ≈ 0.220  (vanilla walk ✓)
 *   ice  (slip 0.98):  high               (ice IS fast ✓)
 *   air: v*0.91 + 0.026 — momentum preserved, slowly decays ✓
 */
public final class MovementPredictor {

    private MovementPredictor() {}

    /** Upper bound on this tick's horizontal distance given last tick + state. */
    public static double predictMaxHorizontal(PlayerData d, Player p) {
        double slip = 0.6;
        if (d.onGround) {
            Material below = p.getLocation().clone().subtract(0, 0.3, 0).getBlock().getType();
            switch (below) {
                case ICE, PACKED_ICE, FROSTED_ICE -> slip = 0.98;
                case BLUE_ICE -> slip = 0.989;
                default -> slip = 0.6;
            }
        }

        double frictionFactor = d.onGround ? 0.91 * slip : 0.91;
        double accel = d.onGround
                ? (p.isSprinting() ? 0.13 : 0.10)
                : (p.isSprinting() ? 0.026 : 0.02);

        if (p.hasPotionEffect(PotionEffectType.SPEED)) {
            int amp = p.getPotionEffect(PotionEffectType.SPEED).getAmplifier();
            accel *= 1.0 + 0.20 * (amp + 1);
        }
        float ws = p.getWalkSpeed();
        if (ws > 0.2001f) accel *= ws / 0.2;     // admin/plugin custom walk speed
        if (p.getFlySpeed() > 0.1001f && p.isFlying()) accel *= p.getFlySpeed() / 0.1;

        double last = d.lastDeltaXZ; // last tick horizontal
        double predicted = last * frictionFactor + accel;

        // Stepping/jumping off a block carries the previous speed for ~1 tick.
        if (d.lastOnGround && !d.onGround) predicted += accel;
        // Sprint-jump impulse (~+0.2 forward on the jump tick).
        if (d.airTicks <= 1 && !d.onGround && p.isSprinting()) predicted += 0.20;

        return predicted;
    }

    // ───────── vertical physics replication ─────────

    /** Gravity per tick (slow-falling lowers it). */
    public static double gravity(Player p) {
        return p.hasPotionEffect(PotionEffectType.SLOW_FALLING) ? 0.01 : 0.08;
    }

    /** Expected next-tick vertical delta from the previous one (air, no jump). */
    public static double expectedDeltaY(PlayerData d, Player p) {
        return (d.lastDeltaY - gravity(p)) * 0.98;
    }

    /** Maximum legal upward velocity off the ground this tick (jump impulse). */
    public static double maxJump(Player p) {
        double j = 0.42;
        if (p.hasPotionEffect(PotionEffectType.JUMP_BOOST)) {
            int amp = p.getPotionEffect(PotionEffectType.JUMP_BOOST).getAmplifier();
            j += 0.1 * (amp + 1);
        }
        return j + 0.05; // slab/scaffold-edge tolerance
    }

    /** True when external forces make vertical prediction unreliable. */
    public static boolean verticalUnsafe(PlayerData d, Player p) {
        long now = System.currentTimeMillis();
        return d.exemptFlying || d.exemptVehicle || d.exemptGliding || d.exemptLiquid
                || d.exemptRiptide || d.exemptClimbing || d.exemptLevitation
                || now - d.lastVelocityMs < 1200 || now - d.lastDamageMs < 800
                || now - d.slimeBounceMs < 1500 || now - d.bubbleColumnMs < 1200
                || now - d.elytraMs < 1500 || now - d.lastTeleportMs < 1500;
    }
}
