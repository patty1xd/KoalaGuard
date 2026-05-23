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
        // Full env exemptions (added exWeb/exClimbing/exSlowFalling/exDead/
        // exSpectator/exLevitation — the use-item branch was running through
        // scaffolding/ladder/cobweb/slow-falling and false-flagging).
        if (ctx.unstable() || ctx.state.exVehicle || ctx.state.exFlying
                || ctx.state.exGliding || ctx.state.exRiptide || ctx.state.exLiquid
                || ctx.state.exClimbing || ctx.state.exSlowFalling
                || ctx.state.exLevitation || ctx.state.exDead || ctx.state.exSpectator
                || ctx.state.exWeb) {
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
        // Sample three Y layers around the player so a player STANDING ON top
        // of a cobweb (feet at integer y, web at y-1) is still detected. The
        // previous floor(y+0.1) feet probe missed that case entirely.
        Material feet  = w.getBlockAt(bx, (int) Math.floor(f.y + 0.1), bz).getType();
        Material legs  = w.getBlockAt(bx, (int) Math.floor(f.y + 1.0), bz).getType();
        Material below = w.getBlockAt(bx, (int) Math.floor(f.y - 0.1), bz).getType();
        double h = f.horizontalSpeed();

        if (feet == Material.COBWEB || legs == Material.COBWEB || below == Material.COBWEB) {
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
        //    Vanilla clamps movement to ~0.0625 b/t × user-input multiplier
        //    while a continuous-use item is being used. Top vanilla speed while
        //    using = sneak-walk (~0.066) or sprint-clamped (~0.165 b/t the
        //    first tick, ~0.07 sustained). The check fires only when measured
        //    horizontal speed is OBVIOUSLY above the vanilla use-clamp — and
        //    the threshold was previously 0.15 which is BELOW vanilla walking
        //    speed (~0.215). That misconfiguration was the user's reported
        //    "alerts on anything". Raised to a value above normal walk and
        //    just below sprint to leave human noise plenty of room.
        //
        //    STALE-FLAG TIMEOUT: `usingItem` is set on USE_ITEM and cleared on
        //    RELEASE_USE_ITEM, but the client doesn't always send the release
        //    (instant-use items, lost packets) — the flag latched forever and
        //    the branch FP'd on everything the player ever did. Hard timeout
        //    on session age past `use-max-session-ms` clears the gate; any
        //    real session re-stamps usingItemSinceNanos on the next USE_ITEM.
        boolean usingContinuous = ctx.state.usingItem
                && (isContinuousUse(ctx.state.inv.mainHand)
                 || isContinuousUse(ctx.state.inv.offHand));
        if (usingContinuous) {
            long useGraceMs   = cfgL("use-grace-ms", 350L);
            long useMaxSessMs = cfgL("use-max-session-ms", 8000L);
            long useAgeMs = (System.nanoTime() - ctx.state.usingItemSinceNanos) / 1_000_000L;
            if (useAgeMs < useGraceMs) {
                // Just started using — let the vanilla velocity clamp catch up
                // before we judge. No clean/dirty either way; neutral tick.
                return;
            }
            if (useAgeMs > useMaxSessMs) {
                // Session age implausibly old → almost certainly a missed
                // RELEASE_USE_ITEM. Treat as not-using and bleed score so the
                // check stops re-firing every tick on the stale latched flag.
                clean(ctx, 1.0);
                return;
            }
            double cap = cfgD("max-use-speed", 0.24);   // > vanilla walk (~0.215)
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
