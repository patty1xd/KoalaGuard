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
 * AntiKnockback-Sprint. Vanilla cancels sprint on damage by routing the
 * inbound EntityVelocity through the client physics tick, which sends
 * ENTITY_ACTION STOP_SPRINTING. Cheats suppress that packet to keep ~30%
 * movement-speed through damage.
 *
 * <p>Critical FP analysis (per research):
 * <ul>
 *   <li>Vanilla does NOT auto-clear sprinting on knockback-LESS damage —
 *       fire, fall, magic, hunger, thorns-without-kb. The client only
 *       reacts to the velocity packet, which those sources don't produce.
 *       Flagging on damage without a knockback would FP on every fire
 *       tick of a sprinting player.</li>
 *   <li>If the player is HOLDING sprint-key, the client sends STOP_SPRINTING
 *       on damage AND then immediately re-sends START_SPRINTING because the
 *       input is still pressed — a legit "kept sprinting" appearance.</li>
 *   <li>STOP_SPRINTING packet timing varies 0-2 ticks across network jitter.</li>
 *   <li>Velocity / speed metric naturally lags 1-3 ticks behind the
 *       sprinting-flag change.</li>
 * </ul>
 *
 * <p>So the original "single damage event + 3 ticks no STOP" version FP'd.
 * This rewrite hardens with three gates that must ALL hold:
 *  S0  Damage event must have had knockback (lastVelocityMs within
 *      {@code kb-window-ms} of lastDamageMs). Filters fire / fall / magic.
 *  S1  No STOP_SPRINTING packet in a generous window after the damage tick
 *      (covers 0-6 tick jitter).
 *  S2  Pattern over MULTIPLE consecutive damage events. A single race-
 *      window miss never confirms — only a player who never stops
 *      sprinting across N hits in a fight is the cheat.
 *
 * Disabled by default: even with these gates the metric is sensitive to
 * server jitter. Enable + tune per server after observing your own logs.
 */
public final class AntiKBSprintCheck extends SimCheck {

    private static final class S {
        long lastEvaluatedDamageNanos = Long.MIN_VALUE;
        int  missStreak;             // consecutive damages with no STOP_SPRINTING
        long lastMissNanos;          // newest damage in the streak
    }

    private final Map<UUID, S> state = new ConcurrentHashMap<>();

    public AntiKBSprintCheck(KoalaGuard plugin) {
        super(plugin, "antikbsprint", CheckCategory.COMBAT,
                "Sprint kept through repeated kb damage (no STOP_SPRINTING)");
    }

    @Override
    public void onTick(CheckContext ctx) {
        if (ctx.unstableBasic()) return;
        if (ctx.state.exFlying || ctx.state.exVehicle || ctx.state.exGliding
                || ctx.state.exLevitation || ctx.state.exRiptide
                || ctx.state.exLiquid) return;

        UUID id = ctx.data.getUuid();
        S s = state.computeIfAbsent(id, k -> new S());

        long lastDamageTick = ctx.state.combat.lastDamageTakenTick;
        if (lastDamageTick < 0) return;
        long lastDamageNanos = ctx.state.combat.lastDamageTakenNanos;
        if (lastDamageNanos == s.lastEvaluatedDamageNanos) return;

        int win = cfgI("post-damage-window-ticks", 6);
        long sinceTick = ctx.state.tick - lastDamageTick;
        if (sinceTick < win) return;

        s.lastEvaluatedDamageNanos = lastDamageNanos;

        // ─── S0 — knockback gate ───
        // Skip damage that did NOT produce a server-side knockback packet.
        // No knockback = vanilla doesn't expect a STOP_SPRINTING anyway, so
        // "still sprinting" is normal. Fire / fall / magic / hunger / suffocate
        // / thorns-without-kb all land here.
        long kbWindowMs = cfgL("kb-window-ms", 250L);
        boolean hadKb = ctx.data.lastVelocityMs > 0
                && Math.abs(ctx.data.lastVelocityMs - ctx.data.lastDamageMs) <= kbWindowMs;
        if (!hadKb) { s.missStreak = 0; clean(ctx, 0.5); return; }

        // ─── S1 — STOP_SPRINTING absence in the window ───
        boolean stopped = false;
        for (CapturedPacket p : ctx.state.log.recent(96)) {
            if (p.kind != PacketKind.ENTITY_ACTION) continue;
            if (!"STOP_SPRINTING".equals(p.strA)) continue;
            if (p.tickIndex >= lastDamageTick && p.tickIndex <= lastDamageTick + win) {
                stopped = true; break;
            }
        }
        if (stopped) { s.missStreak = 0; clean(ctx, 1.0); return; }

        // Still sprinting + actually moving at sprint speed = signal.
        boolean stillSprinting = ctx.state.sprinting;
        double speed = ctx.state.current != null ? ctx.state.current.horizontalSpeed() : 0;
        double minMoveSpeed = cfgD("min-sprint-speed", 0.20);
        if (!stillSprinting || speed <= minMoveSpeed) {
            s.missStreak = 0; clean(ctx, 0.5); return;
        }

        // ─── S2 — sustained multi-damage pattern ───
        // The damage event must be CLOSE-IN-TIME to the previous miss. A miss
        // streak that spans more than a few seconds is two unrelated fights,
        // not a sustained cheat — reset.
        long streakWindowMs = cfgL("streak-window-ms", 4000L);
        if (s.lastMissNanos > 0
                && (lastDamageNanos - s.lastMissNanos) / 1_000_000L > streakWindowMs) {
            s.missStreak = 0;
        }
        s.missStreak++;
        s.lastMissNanos = lastDamageNanos;

        int minMisses = cfgI("min-miss-streak", 3);
        if (s.missStreak < minMisses) return;             // not enough evidence yet

        diverge(ctx, cfgD("score", 6.0), cfgD("threshold", 9.0),
                cfgI("min-streak", 2),
                String.format("kept sprint across %d consecutive kb damages (no STOP_SPRINTING)",
                        s.missStreak),
                false);
    }

    @Override
    public void cleanup(UUID uuid) {
        super.cleanup(uuid);
        state.remove(uuid);
    }
}
