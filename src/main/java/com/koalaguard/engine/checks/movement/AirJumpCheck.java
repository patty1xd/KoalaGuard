package com.koalaguard.engine.checks.movement;

import com.koalaguard.KoalaGuard;
import com.koalaguard.check.CheckCategory;
import com.koalaguard.engine.check.CheckContext;
import com.koalaguard.engine.check.SimCheck;
import com.koalaguard.engine.state.PlayerState;
import com.koalaguard.engine.state.PositionFrame;

/**
 * AirJump / multi-jump. A fresh upward jump impulse can only happen from the
 * ground. If the vertical velocity suddenly flips from falling/flat to a
 * jump-sized positive value while the player has been airborne for several
 * ticks (no ground, no near-ground), that is an impossible second jump.
 * Slime/bed/knockback/levitation/web/elytra are all exempt so it cannot
 * false-positive.
 */
public final class AirJumpCheck extends SimCheck {

    public AirJumpCheck(KoalaGuard plugin) {
        super(plugin, "airjump", CheckCategory.MOVEMENT, "Jumping in mid-air");
    }

    @Override
    public Stage stage() { return Stage.FRAME; }

    @Override
    public void onTick(CheckContext ctx) {
        PlayerState s = ctx.state;
        PositionFrame f = s.current, prev = s.previous;
        if (f == null || prev == null) return;

        if (ctx.unstable() || s.exFlying || s.exGliding || s.exClimbing
                || s.exLiquid || s.exLevitation || s.exSlowFalling
                || s.exRiptide || s.exVehicle || s.exWeb) {
            clean(ctx, 1.0);
            return;
        }
        long now = System.currentTimeMillis();
        // Non-velocity environment graces always apply (legit knockback-less
        // vertical sources the simulator can't model cleanly).
        boolean otherGrace = now - ctx.data.lastDamageMs < 800
                || now - ctx.data.slimeBounceMs < 1500
                || now - ctx.data.lastSlimeOrBedMs < 1500
                || now - ctx.data.bubbleColumnMs < 1200
                || now - ctx.data.lastTeleportMs < 1500
                || s.tick - s.lastSpecialBlockTick < 12;
        if (otherGrace) { return; }

        // Velocity (knockback) grace — honored UNCONDITIONALLY.
        //
        // The previous "anti forged-kb spam" escalation (stop honoring the
        // grace after max-exempt-streak distinct velocity events) was built on
        // a false premise: ENTITY_VELOCITY is a SERVER→CLIENT packet stamped
        // by our own listener from server-side knockback. A cheat client
        // cannot emit or forge it. The only way to accumulate velocity events
        // is to genuinely get hit — and a player who is genuinely being hit
        // is exactly the player whose upward dy is legitimate knockback.
        // The escalation therefore fired ONLY on legit players in sustained
        // fights (9+ hits without a 1.2 s gap — i.e. every real combo), then
        // flagged + SET BACK the victim mid-combo. That was the reported
        // AirJump false positive. Window kept at 1200 ms (kb decay length).
        boolean velExempt = now - ctx.data.lastVelocityMs < 1200;
        if (velExempt) return;

        // Detection-restored: streak=1, impulse=0.36, airTicks=4 — but with a
        // stricter "really airborne" double-check: NONE of the last 4 frames
        // can be simGround. Step-up FP source was one-tick simGround miss
        // surrounded by simGround=true frames; requiring 4 consecutive non-
        // ground frames rules that out without losing real AirJump detection
        // (which produces sustained mid-air physics).
        double impulse = cfgD("min-jump-impulse", 0.36);
        boolean freshJump = f.dy > impulse && prev.dy <= 0.0;
        int needAir = cfgI("min-air-ticks", 4);
        boolean airborne  = !f.simGround && s.airTicks > needAir;
        if (airborne) {
            int sustainedAir = 0;
            for (PositionFrame g : s.recentFrames(needAir + 2)) {
                if (g.simGround) break;
                sustainedAir++;
                if (sustainedAir >= needAir) break;
            }
            if (sustainedAir < needAir) airborne = false;
        }

        if (freshJump && airborne) {
            // Vanilla-impossible. A single confirmed mid-air jump is conclusive
            // (all of {slime/bed/kb/levitation/web/elytra/teleport/special-block/
            // ladder/water/slow-fall} are exempt above). One detection ⇒ flag +
            // immediate setback (the setback path is now synchronous, so the
            // player is rubber-banded the same tick).
            diverge(ctx, cfgD("score", 12.0), cfgD("threshold", 9.0),
                    cfgI("min-streak", 1),
                    String.format("air jump dy=%.3f after %d air ticks", f.dy, s.airTicks),
                    true);
        } else {
            clean(ctx, 2.0);
        }
    }
}
