package com.koalaguard.engine.checks.movement;

import com.koalaguard.KoalaGuard;
import com.koalaguard.check.CheckCategory;
import com.koalaguard.engine.check.CheckContext;
import com.koalaguard.engine.check.SimCheck;
import com.koalaguard.engine.packet.PacketKind;
import com.koalaguard.engine.state.PositionFrame;

/**
 * ElytraFly — DISABLED BY DEFAULT.
 *
 * Real elytra gliding legitimately includes upward motion (pitch up to trade
 * speed for height) and firework boosts, so a naive "climbing" rule
 * false-positives normal flight (exactly what was reported). Detecting it
 * properly needs the full vanilla elytra glide integration; until that is
 * modelled this only flags the blatant, physically-impossible case: a long
 * SUSTAINED climb with NO firework anywhere in a generous window — you cannot
 * keep gaining height on an elytra without a rocket (you stall). Leave
 * disabled unless you accept the residual risk.
 */
public final class ElytraFlyCheck extends SimCheck {

    public ElytraFlyCheck(KoalaGuard plugin) {
        super(plugin, "elytrafly", CheckCategory.MOVEMENT, "Sustained rocketless elytra climb");
    }

    @Override
    public Stage stage() { return Stage.FRAME; }

    @Override
    public void onTick(CheckContext ctx) {
        PositionFrame f = ctx.state.current;
        if (f == null) return;
        if (!ctx.state.exGliding || ctx.unstable()
                || ctx.state.exVehicle || ctx.state.exLevitation || ctx.state.exRiptide) {
            clean(ctx, 1.0);
            return;
        }
        long now = System.currentTimeMillis();
        if (now - ctx.data.lastVelocityMs < 2000) return;

        // Any recent right-click (firework) within a generous window exempts.
        boolean rocket = ctx.state.log.recent(80).stream().anyMatch(p ->
                (p.kind == PacketKind.USE_ITEM || p.kind == PacketKind.BLOCK_PLACE)
                && (System.nanoTime() - p.recvNanos) / 1_000_000L
                    < cfgL("rocket-window-ms", 3000L));
        if (rocket) { clean(ctx, 2.0); return; }

        // Near-zero-FP: gliding state active but no elytra equipped in
        // chestplate slot. Vanilla server gates FALL_FLYING on chest item
        // presence; cheat clients that force the gliding flag client-side
        // leak through this. Single confirmed tick is conclusive.
        try {
            org.bukkit.inventory.ItemStack chest = ctx.player.getInventory().getChestplate();
            if (chest == null || chest.getType() != org.bukkit.Material.ELYTRA) {
                // streak 2: avoid a 1-tick race where the gliding flag lingers
                // for a tick after the elytra leaves the chest slot mid-swap.
                diverge(ctx, cfgD("no-elytra-score", 14.0), cfgD("threshold", 12.0),
                        cfgI("no-elytra-min-streak", 2),
                        "gliding without elytra in chest slot", true);
                return;
            }
            // Durability-zero elytra is also impossible to glide with
            org.bukkit.inventory.meta.Damageable dmg = (org.bukkit.inventory.meta.Damageable) chest.getItemMeta();
            if (dmg != null && chest.getType().getMaxDurability() > 0
                    && chest.getType().getMaxDurability() - dmg.getDamage() <= 1) {
                diverge(ctx, cfgD("broken-elytra-score", 12.0), cfgD("threshold", 12.0),
                        cfgI("broken-elytra-min-streak", 2),
                        "gliding with broken elytra (durability 0)", true);
                return;
            }
        } catch (Throwable ignored) { }

        if (f.dy > cfgD("min-climb-dy", 0.15)) {
            diverge(ctx, cfgD("score", 3.0), cfgD("threshold", 12.0),
                    cfgI("min-streak", 25),
                    String.format("rocketless elytra climb dy=%.3f", f.dy), true);
        } else {
            clean(ctx, 2.0);
        }
    }
}
