package com.koalaguard.engine.checks.combat;

import com.koalaguard.KoalaGuard;
import com.koalaguard.check.CheckCategory;
import com.koalaguard.engine.check.CheckContext;
import com.koalaguard.engine.check.SimCheck;
import com.koalaguard.engine.packet.CapturedPacket;
import com.koalaguard.engine.packet.PacketKind;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * AntiKnockback-Sprint. Vanilla cancels sprint on damage — the client auto-
 * sends ENTITY_ACTION STOP_SPRINTING the instant it processes the inbound
 * EntityVelocity / Explosion / Damage packet (Mojang hard-coded behaviour in
 * the player physics tick). Cheats (Meteor, Wurst, LiquidBounce, Future,
 * FDP) suppress the STOP_SPRINTING to keep the ~30% movement-speed advantage
 * while still taking minimal knockback — a stealthier antikb than full
 * velocity-cancel. VelocityCheck won't catch it because the player IS
 * accepting the kb (just at sprint speed); only the missing STOP_SPRINTING
 * + the post-damage sprinting flag reveal it.
 *
 * Detection: after a damage event, if the player is STILL sprinting
 * {@code post-damage-window-ticks} later (no STOP_SPRINTING / restart-cycle
 * occurred), and they're moving at near-sprint speed, flag.
 *
 * FP-safe: a player who briefly tapped sprint before the hit and stops on
 * their own would clear within the window; a player on slow-falling /
 * levitation / vehicle / gliding doesn't sprint at all.
 */
public final class AntiKBSprintCheck extends SimCheck {

    private static final class S {
        long lastEvaluatedDamageNanos = Long.MIN_VALUE;
    }

    private final Map<UUID, S> state = new ConcurrentHashMap<>();

    public AntiKBSprintCheck(KoalaGuard plugin) {
        super(plugin, "antikbsprint", CheckCategory.COMBAT,
                "Sprint kept through damage (suppressed STOP_SPRINTING)");
    }

    @Override
    public void onTick(CheckContext ctx) {
        if (ctx.unstableBasic()) return;
        if (ctx.state.exFlying || ctx.state.exVehicle || ctx.state.exGliding
                || ctx.state.exLevitation || ctx.state.exRiptide) return;

        UUID id = ctx.data.getUuid();
        S s = state.computeIfAbsent(id, k -> new S());

        long lastDamageTick = ctx.state.combat.lastDamageTakenTick;
        if (lastDamageTick < 0) return;
        // Dedupe by NANOS not tick — tick freezes when the player isn't moving
        // (rotation-only frames don't advance s.tick), so multi-hit damage
        // within a frozen-tick window collapsed onto the same tick number and
        // only the first event ever evaluated. lastDamageTakenNanos is always
        // unique.
        long lastDamageNanos = ctx.state.combat.lastDamageTakenNanos;
        if (lastDamageNanos == s.lastEvaluatedDamageNanos) return;

        int win = cfgI("post-damage-window-ticks", 3);
        long sinceTick = ctx.state.tick - lastDamageTick;
        // Wait for the window after damage before deciding.
        if (sinceTick < win) return;

        s.lastEvaluatedDamageNanos = lastDamageNanos;

        // Within the window, did the client send STOP_SPRINTING? Scan recent
        // ENTITY_ACTION packets bound to ticks in [lastDamageTick, +win].
        boolean stopped = false;
        for (CapturedPacket p : ctx.state.log.recent(64)) {
            if (p.kind != PacketKind.ENTITY_ACTION) continue;
            if (!"STOP_SPRINTING".equals(p.strA)) continue;
            if (p.tickIndex >= lastDamageTick && p.tickIndex <= lastDamageTick + win) {
                stopped = true; break;
            }
        }

        // Also verify the player IS still sprinting now AND actually moving
        // at near-sprint speed. A stationary sprint state without movement
        // doesn't gain any advantage and might be lag.
        boolean stillSprinting = ctx.state.sprinting;
        double speed = ctx.state.current != null ? ctx.state.current.horizontalSpeed() : 0;
        double minMoveSpeed = cfgD("min-sprint-speed", 0.20);

        if (!stopped && stillSprinting && speed > minMoveSpeed) {
            diverge(ctx, cfgD("score", 6.0), cfgD("threshold", 9.0),
                    cfgI("min-streak", 2),
                    String.format("kept sprint %d ticks past damage @tick=%d speed=%.3f",
                            sinceTick, lastDamageTick, speed),
                    false);
        } else {
            clean(ctx, 1.0);
        }
    }

    @Override
    public void cleanup(UUID uuid) {
        super.cleanup(uuid);
        state.remove(uuid);
    }
}
