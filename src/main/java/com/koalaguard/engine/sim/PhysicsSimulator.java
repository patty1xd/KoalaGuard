package com.koalaguard.engine.sim;

import com.koalaguard.data.PlayerData;
import com.koalaguard.engine.state.PlayerState;
import com.koalaguard.engine.state.PositionFrame;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

/**
 * Vanilla-like server-side prediction. Rather than hard-coding "max speed"
 * constants, it integrates the player's OWN previous velocity through the
 * vanilla friction/acceleration recurrence:
 *
 *     v_next = v_prev * friction + acceleration
 *
 * so knockback momentum, ice, sprint-jumps and potions are all *modelled*,
 * not whitelisted. The output is a strict upper envelope: legitimate play
 * stays inside it every tick, cheats fall outside it persistently.
 */
public final class PhysicsSimulator {

    private PhysicsSimulator() {}

    public static SimResult simulate(PlayerData data, Player player) {
        PlayerState s = data.engine;
        SimResult r = new SimResult();

        PositionFrame prev = s.previous;
        PositionFrame cur  = s.current;
        if (prev == null || cur == null) {
            r.horizontalReliable = r.verticalReliable = false;
            return r;
        }

        // SERVER-AUTHORITATIVE ground: the client onGround flag is spoofable
        // (fly/NoFall send onGround=true while airborne to get the lenient
        // band). We believe it ONLY when server geometry agrees the player is
        // genuinely near ground — which also avoids a false positive when the
        // footprint collision scan narrowly misses on a slab/stair edge.
        double ph = player.isSneaking() ? 1.5 : 1.8;
        boolean nearPrev = CollisionEngine.nearGround(
                player.getWorld(), prev.x, prev.y, prev.z, ph);
        boolean onGround = prev.simGround || (prev.clientGround && nearPrev);
        double slip = onGround ? cur.groundSlipperiness : 0.6;

        // ── horizontal envelope ──
        double friction = onGround ? 0.91 * slip : 0.91;
        boolean sprint = s.sprinting;
        double accel = onGround ? (sprint ? 0.13 : 0.10)
                                : (sprint ? 0.026 : 0.02);

        // Race-safe potion read: hasPotionEffect → getPotionEffect can return
        // null if the effect ticks off between the two calls (NPE crashed the
        // engine tick for that player, skipping every remaining check).
        var spd = player.getPotionEffect(PotionEffectType.SPEED);
        if (spd != null) {
            accel *= 1.0 + 0.20 * (spd.getAmplifier() + 1);
        }
        if (player.hasPotionEffect(PotionEffectType.DOLPHINS_GRACE)) accel *= 1.5;
        float ws = player.getWalkSpeed();
        if (ws > 0.2001f) accel *= ws / 0.2;
        if (player.isFlying() && player.getFlySpeed() > 0.0501f) accel *= player.getFlySpeed() / 0.05;

        double lastH = prev.horizontalSpeed();
        double predicted = lastH * friction + accel;

        // Edge cases that legitimately add momentum for one tick.
        if (prev.simGround && !cur.simGround) predicted += accel;          // step/jump off
        if (s.airTicks <= 1 && !cur.simGround && sprint) predicted += 0.20; // sprint-jump impulse

        // Server-applied knockback the player is allowed to carry.
        Vector kb = data.pendingVelocity;
        if (kb != null && System.currentTimeMillis() - data.pendingVelocityMs < 1500) {
            predicted += Math.hypot(kb.getX(), kb.getZ()) + 0.05;
        }

        // diagonal-strafe + sub-tick aliasing slack (kept tight, FP-safe).
        r.maxHorizontal = predicted * 1.06 + 0.05;

        // Min plausible horizontal: friction alone can't drop velocity faster
        // than friction*lastH; if input was released this tick. Below 0.005
        // momentum is cancelled by Mojang's epsilon. Used for slow-fly /
        // stop-cheat detection — actualH < minHorizontal means the player
        // teleport-snapped to zero motion the simulator's friction wouldn't.
        double frictionFloor = lastH * friction;
        if (frictionFloor < 0.005) frictionFloor = 0;
        r.minHorizontal = frictionFloor * 0.94 - 0.005;

        // ── vertical envelope ──
        double gravity = player.hasPotionEffect(PotionEffectType.SLOW_FALLING) ? 0.01 : 0.08;
        if (player.hasPotionEffect(PotionEffectType.LEVITATION)) {
            r.verticalReliable = false;
        }
        double expectedDy = (prev.dy - gravity) * 0.98;
        double jumpImpulse = 0.42;
        var jb = player.getPotionEffect(PotionEffectType.JUMP_BOOST);
        if (jb != null) {
            jumpImpulse += 0.1 * (jb.getAmplifier() + 1);
        }

        r.expectedDy = expectedDy;
        if (onGround) {
            // could be standing (≈0), auto-stepping (vanilla max-up-step 0.6),
            // or jumping (jumpImpulse). Cover the step so stairs/slabs are FP-free.
            r.dyLow  = -0.5;
            r.dyHigh = Math.max(jumpImpulse, 0.6) + 0.10;
        } else {
            r.dyLow  = expectedDy - 0.05;
            r.dyHigh = expectedDy + 0.05;
        }

        // Forces the simulator does not model precisely → mark unreliable so
        // the divergence checks skip rather than risk a false positive.
        if (s.exVehicle || s.exGliding || s.exClimbing || s.exLiquid
                || s.exRiptide || s.exLevitation || s.exWeb) {
            r.horizontalReliable = false;
            r.verticalReliable = false;
        }
        if (kb != null && System.currentTimeMillis() - data.pendingVelocityMs < 1500) {
            r.verticalReliable = false;     // knockback Y handled by VelocityCheck
        }
        return r;
    }

    /**
     * Knockback the client must physically take, given a server-applied
     * velocity. Used by VelocityCheck to verify the player obeyed it instead
     * of timing the hit.
     */
    public static double[] expectedKnockbackTravel(Vector kb) {
        double h = Math.hypot(kb.getX(), kb.getZ());
        return new double[]{ h, kb.getY() };
    }
}
