package com.koalaguard.engine.checks.movement;

import com.koalaguard.KoalaGuard;
import com.koalaguard.check.CheckCategory;
import com.koalaguard.engine.check.CheckContext;
import com.koalaguard.engine.check.SimCheck;
import com.koalaguard.engine.state.PositionFrame;
import org.bukkit.potion.PotionEffectType;

/**
 * InputSanity — sprint state must obey vanilla input rules. 1.21.2+ clients
 * report their keyboard bitfield via PLAYER_INPUT, which gives the server the
 * ground truth vanilla derives sprint from:
 *
 *  • Vanilla stops sprinting the moment forward impulse drops (releasing W);
 *    a SUSTAINED sprint at speed with no forward key held is omni-sprint —
 *    the classic "sprint in every direction" module.
 *  • Vanilla cannot sprint under blindness; a sustained blind sprint is the
 *    same module family ignoring the effect gate.
 *
 * The input rule is gated on {@code inputSeen} so clients that never send
 * PLAYER_INPUT (pre-1.21.2 protocol via ViaVersion, decode failure) are never
 * evaluated against it. Both rules demand long consecutive streaks: the 1-2
 * tick lag between an input change and the STOP_SPRINTING action packet, and
 * post-release momentum, never survive the streak gate.
 */
public final class InputSanityCheck extends SimCheck {

    public InputSanityCheck(KoalaGuard plugin) {
        super(plugin, "inputsanity", CheckCategory.MOVEMENT,
                "Sprint state contradicts reported input / status effects");
    }

    @Override
    public void onTick(CheckContext ctx) {
        PositionFrame f = ctx.state.current;
        if (f == null) return;
        // Only judge ticks that actually produced a movement frame — on an
        // idle tick `current` is a stale frame whose speed no longer matches
        // the live sprint/input state.
        if (ctx.state.framesThisTick == 0) return;

        if (ctx.unstable() || ctx.state.exVehicle || ctx.state.exGliding
                || ctx.state.exFlying || ctx.state.exRiptide
                || ctx.state.exLiquid || ctx.state.exLevitation) {
            clean(ctx, 1.0);
            return;
        }
        // Swim-sprint shares the sprint flag with different input rules —
        // grace a window after leaving water, same idea as Spider's.
        if (ctx.tick - ctx.state.lastLiquidTick < cfgI("liquid-grace-ticks", 20)) {
            clean(ctx, 1.0);
            return;
        }

        boolean sprinting = ctx.state.sprinting;
        double speed = f.horizontalSpeed();
        if (!sprinting || speed < cfgD("min-speed", 0.15)) {
            clean(ctx, 1.0);
            return;
        }

        // ── Rule 1: sprint without forward input (omni-sprint) ──
        if (ctx.state.inputSeen && (ctx.state.inputMask & 1) == 0) {
            diverge(ctx, cfgD("score", 3.0), cfgD("threshold", 12.0),
                    cfgI("min-streak", 8),
                    String.format("sprinting at %.2f b/t with no forward input (mask=%d)",
                            speed, ctx.state.inputMask), true);
            return;
        }

        // ── Rule 2: sprint under blindness ──
        if (ctx.player.hasPotionEffect(PotionEffectType.BLINDNESS)) {
            diverge(ctx, cfgD("blind-score", 3.0), cfgD("threshold", 12.0),
                    cfgI("blind-min-streak", 10),
                    String.format("sprinting at %.2f b/t while blind", speed), true);
            return;
        }

        clean(ctx, 1.0);
    }
}
