package com.koalaguard.engine.checks.movement;

import com.koalaguard.KoalaGuard;
import com.koalaguard.check.CheckCategory;
import com.koalaguard.engine.check.CheckContext;
import com.koalaguard.engine.check.SimCheck;
import org.bukkit.Material;
import org.bukkit.entity.Boat;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * BoatFly / VehicleFly. The classic vehicle-exploit hack: a player mounts a
 * boat (or any rideable) and the cheat manipulates the vehicle's motionY so
 * pressing jump lifts the boat — climbing without limit, NCP-bypassable since
 * the player is in a vehicle (so every movement check exempts them).
 *
 * The engine's movement checks all bail when {@code exVehicle} is true, which
 * is exactly what makes BoatFly work. This check is the one that does NOT
 * exempt — it inspects the vehicle entity's vertical motion directly. A real
 * boat on water has dy ≈ 0; sliding off a slope produces a brief negative dy
 * and recovers; a riptide is exempt; an entity teleport / explosion stamps a
 * grace. A sustained POSITIVE dy on a boat that is not in water is vanilla-
 * impossible.
 *
 * Variants covered:
 *   • BoatFly (Wurst, LiquidBounce, Meteor "VehicleFly", Sigma BoatFlight, etc.)
 *   • MinecartFly (rare; same idea)
 *   • Pig/Horse fly via attribute injection
 *
 * Confirms on a streak so a brief boat bounce never trips.
 */
public final class VehicleFlyCheck extends SimCheck {

    private static final class S {
        double lastY = Double.NaN;
        double accUp = 0;
        int    upTicks;
    }

    private final Map<UUID, S> state = new ConcurrentHashMap<>();

    public VehicleFlyCheck(KoalaGuard plugin) {
        super(plugin, "vehiclefly", CheckCategory.MOVEMENT,
                "Manipulated vehicle vertical motion (BoatFly etc.)");
    }

    @Override
    public void onTick(CheckContext ctx) {
        if (ctx.unstableBasic()) return;
        Player p = ctx.player;
        Entity v = p.getVehicle();
        UUID id = ctx.data.getUuid();
        S s = state.computeIfAbsent(id, k -> new S());

        if (v == null) {
            s.lastY = Double.NaN; s.accUp = 0; s.upTicks = 0;
            clean(ctx, 1.0);
            return;
        }

        // Boats are the canonical target but any rideable that gains height
        // out of water / not on a slope is suspect.
        boolean boat = v instanceof Boat;
        if (!boat && !cfgB("any-vehicle", false)) {
            // Default: only check boats — pigs/horses can legitimately climb
            // stairs and are noisy. Enable any-vehicle for paranoid servers.
            s.lastY = Double.NaN; s.accUp = 0; s.upTicks = 0;
            return;
        }

        // Vehicle situational exempts.
        if (v.isInWater() || v.isInLava()) {
            s.lastY = Double.NaN; s.accUp = 0; s.upTicks = 0;
            clean(ctx, 1.0);
            return;
        }
        // Probe THREE Y offsets below the vehicle (0.05 / 0.30 / 0.70) so a
        // boat exiting water — half-on, half-off where the immediate-below
        // block flickers between water and sand for 2-3 ticks — doesn't
        // false-flag on the buoyancy rebound. If ANY of the probes returns
        // water/bubble/lava, treat as water-state and reset the climb
        // accumulator.
        org.bukkit.Location vl = v.getLocation();
        boolean nearLiquid = false;
        for (double offY : new double[]{ 0.05, 0.30, 0.70 }) {
            Material m = vl.clone().subtract(0, offY, 0).getBlock().getType();
            if (m == Material.WATER || m == Material.BUBBLE_COLUMN
                    || m == Material.LAVA || m == Material.KELP || m == Material.KELP_PLANT
                    || m == Material.SEAGRASS || m == Material.TALL_SEAGRASS) {
                nearLiquid = true;
                break;
            }
        }
        if (nearLiquid) {
            s.lastY = Double.NaN; s.accUp = 0; s.upTicks = 0;
            return;
        }
        long now = System.currentTimeMillis();
        if (now - ctx.data.lastTeleportMs   < 2000
         || now - ctx.data.lastWorldChangeMs < 4000
         || now - ctx.data.lastVelocityMs    < 1500) return;

        double y = v.getLocation().getY();
        if (Double.isNaN(s.lastY)) { s.lastY = y; return; }
        double dy = y - s.lastY;
        s.lastY = y;

        Vector vel = v.getVelocity();
        // Sustained climb signal: positive dy with no surface or fluid support.
        if (dy > cfgD("min-up", 0.08) && vel.getY() >= -0.05) {
            s.accUp += dy;
            s.upTicks++;
        } else if (dy < -0.05 || s.upTicks == 0) {
            s.accUp = Math.max(0, s.accUp - 0.10);
            s.upTicks = Math.max(0, s.upTicks - 1);
        }

        if (s.upTicks >= cfgI("min-up-ticks", 4)
                && s.accUp >= cfgD("min-climb", 0.6)) {
            diverge(ctx, cfgD("score", 6.0), cfgD("threshold", 9.0),
                    cfgI("min-streak", 2),
                    String.format("vehicle climb dy=%.3f acc=%.2f ticks=%d (%s)",
                            dy, s.accUp, s.upTicks, v.getType()),
                    true);
            s.accUp = 0;
            s.upTicks = 0;
        }
    }

    @Override
    public void cleanup(UUID uuid) {
        super.cleanup(uuid);
        state.remove(uuid);
    }
}
