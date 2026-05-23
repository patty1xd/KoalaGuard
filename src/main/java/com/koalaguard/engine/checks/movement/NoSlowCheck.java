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

        // ── Use-item NoSlow (eat/drink/bow/crossbow/shield/spyglass).
        //    Vanilla forces ~0.04 b/t (≥70% slowdown) while a continuous-use
        //    item is being used. A NoSlow cheat keeps the player at sprint /
        //    walk speed. The old item-use detection FP'd on walking-while-
        //    eating (which is slow anyway); this gate is high enough that ONLY
        //    sustained near-sprint speed mid-use trips it — humans cannot do
        //    that in vanilla. Catches NoSlowdown / EatHack / BowSpeed across
        //    Wurst, LiquidBounce, Meteor, Sigma.
        //
        //    USE-ONSET GRACE: when USE_ITEM is first sent the client doesn't
        //    immediately clamp velocity — there's a 1-3 tick window where the
        //    player is still finishing their previous sprint frame. Without a
        //    grace the check false-alerts on EVERY legit eat-while-sprinting
        //    that the user pointed out. Wait until the use-session has been
        //    open for at least `use-grace-ms` before checking.
        boolean usingContinuous = ctx.state.usingItem
                && (isContinuousUse(ctx.state.inv.mainHand)
                 || isContinuousUse(ctx.state.inv.offHand));
        if (usingContinuous) {
            long useGraceMs = cfgL("use-grace-ms", 350L);
            long useAgeMs = (System.nanoTime() - ctx.state.usingItemSinceNanos) / 1_000_000L;
            if (useAgeMs < useGraceMs) {
                // Just started using — let the vanilla velocity clamp catch up
                // before we judge. No clean/dirty either way; neutral tick.
                return;
            }
            double cap = cfgD("max-use-speed", 0.15);
            if (h > cap) {
                diverge(ctx, (h - cap) * cfgD("use-score-scale", 30.0),
                        cfgD("threshold", 9.0), cfgI("use-min-streak", 6),
                        String.format("use-item speed %.3f > %.2f (using %s for %dms)",
                                h, cap, ctx.state.inv.mainHand, useAgeMs), true);
                return;
            }
        }
        clean(ctx, 1.0);
    }

    private static boolean isContinuousUse(Material m) {
        if (m == null) return false;
        if (m.isEdible()) return true;
        return switch (m) {
            case BOW, CROSSBOW, SHIELD, TRIDENT, SPYGLASS, GOAT_HORN, BRUSH,
                 POTION, MILK_BUCKET, HONEY_BOTTLE -> true;
            default -> false;
        };
    }

    private boolean hasSoulSpeed(CheckContext ctx) {
        try {
            var boots = ctx.player.getInventory().getBoots();
            return boots != null && boots.getEnchantmentLevel(Enchantment.SOUL_SPEED) > 0;
        } catch (Throwable t) { return false; }
    }
}
