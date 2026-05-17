package com.koalaguard.engine.checks.movement;

import com.koalaguard.KoalaGuard;
import com.koalaguard.check.CheckCategory;
import com.koalaguard.engine.check.CheckContext;
import com.koalaguard.engine.check.SimCheck;
import com.koalaguard.engine.state.PositionFrame;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.enchantments.Enchantment;

/**
 * NoSlow — the real meaning: NOT being slowed by a block that must slow you.
 *
 * Vanilla all but freezes you in a COBWEB (effective horizontal speed
 * ≲0.05/tick) and clamps SOUL SAND to ~40% speed. A NoSlow/Cobweb hack moves
 * through them at normal speed — a huge, unambiguous divergence, so this is
 * false-positive proof (a legit player in a web is nearly stationary).
 *
 * The old item-use logic was removed: it false-positived eating-while-walking
 * and overlaps the prediction envelope anyway.
 */
public final class NoSlowCheck extends SimCheck {

    public NoSlowCheck(KoalaGuard plugin) {
        super(plugin, "noslow", CheckCategory.MOVEMENT, "Not slowed by cobweb / soul sand");
    }

    @Override
    public Stage stage() { return Stage.FRAME; }

    @Override
    public void onTick(CheckContext ctx) {
        PositionFrame f = ctx.state.current;
        if (f == null) return;
        if (ctx.unstable() || ctx.state.exVehicle || ctx.state.exFlying
                || ctx.state.exGliding || ctx.state.exRiptide || ctx.state.exLiquid) {
            clean(ctx, 1.0);
            return;
        }
        long now = System.currentTimeMillis();
        if (now - ctx.data.lastVelocityMs < 1500 || now - ctx.data.lastDamageMs < 1000
                || now - ctx.data.lastTeleportMs < 1500) {
            return;
        }

        World w = ctx.player.getWorld();
        int bx = (int) Math.floor(f.x), bz = (int) Math.floor(f.z);
        Material feet = w.getBlockAt(bx, (int) Math.floor(f.y + 0.1), bz).getType();
        Material legs = w.getBlockAt(bx, (int) Math.floor(f.y + 1.0), bz).getType();
        Material below = w.getBlockAt(bx, (int) Math.floor(f.y - 0.1), bz).getType();
        double h = f.horizontalSpeed();

        if (feet == Material.COBWEB || legs == Material.COBWEB) {
            double max = cfgD("max-web-speed", 0.12);   // legit web ≈ 0.05
            if (h > max) {
                diverge(ctx, (h - max) * cfgD("score-scale", 50.0),
                        cfgD("threshold", 9.0), cfgI("min-streak", 4),
                        String.format("cobweb speed %.3f > %.2f", h, max), true);
                return;
            }
            clean(ctx, 1.5);
            return;
        }

        if (below == Material.SOUL_SAND && !hasSoulSpeed(ctx)) {
            double max = cfgD("max-soulsand-speed", 0.16);
            if (h > max) {
                diverge(ctx, (h - max) * cfgD("score-scale", 50.0),
                        cfgD("threshold", 9.0), cfgI("min-streak", 6),
                        String.format("soul sand speed %.3f > %.2f", h, max), true);
                return;
            }
        }
        clean(ctx, 1.0);
    }

    private boolean hasSoulSpeed(CheckContext ctx) {
        try {
            var boots = ctx.player.getInventory().getBoots();
            return boots != null && boots.getEnchantmentLevel(Enchantment.SOUL_SPEED) > 0;
        } catch (Throwable t) { return false; }
    }
}
