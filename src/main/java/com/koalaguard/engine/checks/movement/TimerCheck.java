package com.koalaguard.engine.checks.movement;

import com.koalaguard.KoalaGuard;
import com.koalaguard.check.CheckCategory;
import com.koalaguard.engine.check.CheckContext;
import com.koalaguard.engine.check.SimCheck;
import com.koalaguard.engine.util.TimerBalance;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Timer — the client's game clock runs faster than real time, so EVERYTHING
 * (movement, attacks, item use) happens at a multiplied rate while each
 * individual packet still looks vanilla. The tell is the packet rate itself:
 * a vanilla client emits exactly one movement-class packet per client tick,
 * so the long-run rate over REAL time is a hard 20/s.
 *
 * {@link TimerBalance} accumulates (packets received − packets allowed by
 * elapsed wall time). TCP stalls go negative then catch back up to ~zero;
 * only a fast client clock can push the balance persistently positive. The
 * threshold (default +25 ticks = 1.25 s ahead of reality) is far beyond any
 * transport artifact surviving the debt floor.
 *
 * Resets (not just skips) while in a vehicle and on teleport/join/world-change
 * grace, because the movement-packet cadence around those transitions is not
 * the clean one-per-tick contract.
 */
public final class TimerCheck extends SimCheck {

    private final Map<UUID, TimerBalance> balances = new ConcurrentHashMap<>();

    public TimerCheck(KoalaGuard plugin) {
        super(plugin, "timer", CheckCategory.MOVEMENT,
                "Game clock running faster than real time (timer)");
    }

    @Override
    public void onTick(CheckContext ctx) {
        TimerBalance tb = balances.computeIfAbsent(ctx.data.getUuid(), k -> new TimerBalance());

        long now = System.currentTimeMillis();
        boolean graced = now - ctx.data.joinMs           < 5000
                      || now - ctx.data.lastTeleportMs   < 1500
                      || now - ctx.data.lastWorldChangeMs < 4000
                      || now - ctx.data.lastRespawnMs    < 2500;

        double bal = tb.sample(System.nanoTime(), ctx.state.movePacketCount.get(),
                cfgD("rate-tolerance", 1.005), cfgD("debt-floor", -90.0));

        if (graced || ctx.state.exVehicle) {
            // The one-packet-per-tick contract doesn't hold across these
            // transitions (teleport-confirm reposition, vehicle ride packets),
            // so the accumulated surplus is discarded, not just ignored.
            tb.reset();
            clean(ctx, 1.0);
            return;
        }

        double max = cfgD("max-balance", 25.0);
        if (bal > max) {
            boolean confirmed = diverge(ctx, cfgD("score", 5.0),
                    cfgD("threshold", 9.0), cfgI("min-streak", 3),
                    String.format("client %.1f ticks ahead of real time", bal), true);
            if (confirmed) tb.reset();
        } else {
            clean(ctx, 0.5);
        }
    }

    @Override
    public void cleanup(UUID uuid) {
        super.cleanup(uuid);
        balances.remove(uuid);
    }
}
