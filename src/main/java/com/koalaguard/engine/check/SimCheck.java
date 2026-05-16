package com.koalaguard.engine.check;

import com.koalaguard.KoalaGuard;
import com.koalaguard.check.Check;
import com.koalaguard.check.CheckCategory;
import com.koalaguard.engine.violation.ViolationScore;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Base of every engine check. Extends the kept {@link Check} so the existing
 * alert / Discord / punishment / ban infrastructure works unchanged, but the
 * driving model is completely different: {@link #onTick} is called every
 * server tick with reconstructed state — never from a Bukkit event.
 *
 * Each check owns ONLY its own {@link ViolationScore} per player, which makes
 * the checks fully modular and independent: removing or disabling one cannot
 * affect another.
 */
public abstract class SimCheck extends Check {

    private final Map<UUID, ViolationScore> scores = new ConcurrentHashMap<>();

    protected SimCheck(KoalaGuard plugin, String name, CheckCategory category, String description) {
        super(plugin, name, category, description);
    }

    /**
     * FRAME checks run once per reconstructed client movement tick (the unit
     * the physics is integrated in); TICK checks run once per server tick
     * after the whole packet batch is replayed (transition/aggregate logic).
     */
    public enum Stage { FRAME, TICK }

    public Stage stage() { return Stage.TICK; }

    /** Called by the engine with reconstructed state — never from an event. */
    public abstract void onTick(CheckContext ctx);

    protected ViolationScore score(CheckContext ctx) {
        return scores.computeIfAbsent(ctx.data.getUuid(), k -> new ViolationScore());
    }

    /**
     * Confirm a persistent violation: raise the divergence score and, only if
     * it has stayed elevated long enough, fire the (kept) violation pipeline.
     *
     * @param setback also rubber-band the player to the last sim-valid spot.
     */
    protected void diverge(CheckContext ctx, double amount, double threshold,
                           int minStreak, String detail, boolean setback) {
        ViolationScore vs = score(ctx);
        vs.add(amount);
        if (vs.consume(threshold, minStreak, ctx.tick, cooldownTicks())) {
            if (setback) failAndSetback(ctx.data, ctx.player, detail);
            else         fail(ctx.data, ctx.player, detail);
        }
    }

    /** A clean tick — bleed this check's score. */
    protected void clean(CheckContext ctx, double amount) {
        ViolationScore vs = scores.get(ctx.data.getUuid());
        if (vs != null) {
            vs.recover(amount);
            reward(ctx.data);
        }
    }

    protected int cooldownTicks() {
        return cfgI("confirm-cooldown-ticks", 20);
    }

    public void cleanup(UUID uuid) {
        scores.remove(uuid);
    }
}
