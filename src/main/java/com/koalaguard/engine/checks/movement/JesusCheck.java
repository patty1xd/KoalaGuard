package com.koalaguard.engine.checks.movement;

import com.koalaguard.KoalaGuard;
import com.koalaguard.check.CheckCategory;
import com.koalaguard.engine.check.CheckContext;
import com.koalaguard.engine.check.SimCheck;
import com.koalaguard.engine.state.PositionFrame;
import org.bukkit.Material;
import org.bukkit.World;

/**
 * Jesus / water-walk. The player moves horizontally on a liquid surface while
 * NOT submerged and NOT supported by any solid. Boats (vehicle), Frost Walker
 * (ice underfoot), lily pads and full submersion are all excluded, so only the
 * impossible "running on top of water" state remains. Needs persistence.
 */
public final class JesusCheck extends SimCheck {

    public JesusCheck(KoalaGuard plugin) {
        super(plugin, "jesus", CheckCategory.MOVEMENT, "Walking on liquid");
    }

    @Override
    public Stage stage() { return Stage.FRAME; }

    @Override
    public void onTick(CheckContext ctx) {
        PositionFrame f = ctx.state.current;
        if (f == null) return;
        if (ctx.unstable() || ctx.state.exVehicle || ctx.state.exFlying
                || ctx.state.exGliding || ctx.state.exLiquid || ctx.state.exLevitation
                || ctx.state.exRiptide || f.simGround) {
            clean(ctx, 1.0);
            return;
        }
        World w = ctx.player.getWorld();
        int bx = (int) Math.floor(f.x), bz = (int) Math.floor(f.z);
        Material feet = w.getBlockAt(bx, (int) Math.floor(f.y - 0.05), bz).getType();
        Material at   = w.getBlockAt(bx, (int) Math.floor(f.y + 0.1), bz).getType();
        Material below= w.getBlockAt(bx, (int) Math.floor(f.y - 1.0), bz).getType();

        boolean onLiquid = (feet == Material.WATER || feet == Material.LAVA);
        boolean lilypad  = at == Material.LILY_PAD;
        boolean frost    = below == Material.FROSTED_ICE || below == Material.ICE
                || below == Material.PACKED_ICE || below == Material.BLUE_ICE;

        if (onLiquid && !lilypad && !frost
                && Math.abs(f.dy) < cfgD("max-dy", 0.12)
                && f.horizontalSpeed() > cfgD("min-speed", 0.08)) {
            diverge(ctx, cfgD("score", 5.0), cfgD("threshold", 12.0),
                    cfgI("min-streak", 6),
                    String.format("on %s dy=%.3f h=%.3f", feet, f.dy, f.horizontalSpeed()),
                    true);
            return;
        }

        // Dolphin / Bounce-Jesus: the cheat bounces ON the water surface
        // instead of holding y still — each tick alternates up/down so the
        // mean |dy| stays small but no single frame meets the flat threshold
        // above. Catches it by averaging dy magnitude over the recent window:
        // a vanilla swimmer continually loses height (sinks) → big maxDown;
        // a vanilla water-jumper sinks BETWEEN jumps; the bouncer never goes
        // below the surface, so its accumulated descent stays near zero.
        if (onLiquid && !lilypad && !frost && f.horizontalSpeed() > 0.06) {
            double accDown = 0;
            int samples = 0;
            for (var g : ctx.state.recentFrames(cfgI("dolphin-scan", 16))) {
                if (g.dy < -0.01) accDown += -g.dy;
                samples++;
            }
            if (samples >= 10 && accDown < cfgD("dolphin-max-descent", 0.40)) {
                diverge(ctx, cfgD("dolphin-score", 4.0), cfgD("threshold", 12.0),
                        cfgI("dolphin-min-streak", 4),
                        String.format("dolphin-walk on %s: accDown=%.2f n=%d",
                                feet, accDown, samples),
                        true);
                return;
            }
        }
        clean(ctx, 1.5);
    }
}
