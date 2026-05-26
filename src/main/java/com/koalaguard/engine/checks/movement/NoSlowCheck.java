package com.koalaguard.engine.checks.movement;

import com.koalaguard.KoalaGuard;
import com.koalaguard.check.CheckCategory;
import com.koalaguard.engine.check.CheckContext;
import com.koalaguard.engine.check.SimCheck;
import com.koalaguard.engine.state.PositionFrame;
import org.bukkit.Material;
import org.bukkit.World;

/**
 * NoSlow — COBWEB-ONLY. Vanilla all but freezes the player inside a cobweb
 * (effective horizontal speed ≲0.05 / tick); a NoSlow / Cobweb hack moves
 * through it at normal speed. The divergence is huge and unambiguous, so
 * this check is false-positive proof: only fires when the player is
 * physically standing in / on / under a cobweb block AND their horizontal
 * speed is well above the vanilla cap.
 *
 * Removed by request (kept producing FPs the user explicitly reported):
 *   • soul-sand bypass — vanilla soul-sand handling has too many edge
 *     conditions (slabs, carpets, water-logged, soul-speed boots) for a
 *     stable check at our threshold.
 *   • use-item bypass (eat / bow / shield while sprinting) — false-flagged
 *     legit eat-while-walking onset frames. PredictionCheck's envelope
 *     handles the broader case.
 */
public final class NoSlowCheck extends SimCheck {

    public NoSlowCheck(KoalaGuard plugin) {
        super(plugin, "noslow", CheckCategory.MOVEMENT, "Not slowed by cobweb");
    }

    @Override
    public Stage stage() { return Stage.FRAME; }

    @Override
    public void onTick(CheckContext ctx) {
        PositionFrame f = ctx.state.current;
        if (f == null) return;
        if (ctx.unstable() || ctx.state.exVehicle || ctx.state.exFlying
                || ctx.state.exGliding || ctx.state.exRiptide || ctx.state.exLiquid
                || ctx.state.exClimbing || ctx.state.exSlowFalling
                || ctx.state.exLevitation || ctx.state.exDead || ctx.state.exSpectator) {
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
        // Sample three Y layers so standing-ON-top-of-web is also detected.
        Material feet  = w.getBlockAt(bx, (int) Math.floor(f.y + 0.1), bz).getType();
        Material legs  = w.getBlockAt(bx, (int) Math.floor(f.y + 1.0), bz).getType();
        Material below = w.getBlockAt(bx, (int) Math.floor(f.y - 0.1), bz).getType();

        boolean inWeb = feet == Material.COBWEB
                     || legs == Material.COBWEB
                     || below == Material.COBWEB;
        if (!inWeb) { clean(ctx, 1.0); return; }

        double h = f.horizontalSpeed();
        double max = cfgD("max-web-speed", 0.12);    // legit web ≈ 0.05
        if (h > max) {
            diverge(ctx, (h - max) * cfgD("score-scale", 50.0),
                    cfgD("threshold", 9.0), cfgI("min-streak", 4),
                    String.format("cobweb speed %.3f > %.2f", h, max), true);
        } else {
            clean(ctx, 1.5);
        }
    }
}
