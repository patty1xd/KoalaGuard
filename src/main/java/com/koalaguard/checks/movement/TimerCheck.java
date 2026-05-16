package com.koalaguard.checks.movement;

import com.koalaguard.KoalaGuard;
import com.koalaguard.check.MovementCheck;
import com.koalaguard.data.PlayerData;
import org.bukkit.entity.Player;

/**
 * Timer — authoritative-clock model.
 *
 * The server tick is authoritative (advanced once per real tick by the
 * TransactionManager). The vanilla client sends ≈1 movement packet per tick.
 * We accumulate (clientPackets − serverTicks): a timer makes the client
 * out-run the server clock so the balance climbs; honest jitter cancels out;
 * and because BOTH counters slow together under server lag, lag can never be
 * mistaken for a timer. Also hard-gated on TPS for extra safety.
 */
public final class TimerCheck extends MovementCheck {

    public TimerCheck(KoalaGuard plugin) {
        super(plugin, "timer", "Client clock running ahead of the server (timer)");
    }

    @Override
    public void handle(PlayerData d, Player player) {
        if (d.exemptVehicle || d.exemptGliding || d.exemptRiptide) { d.setBuffer(k("bal"), 0); return; }
        if (plugin.getMetrics().tps() < 19.0) { d.setBuffer(k("bal"), 0); return; }

        long ticks = d.serverTicks;
        long packets = d.flyingPacketCount;
        long lastT = d.getLong(k("lt"));
        long lastP = d.getLong(k("lp"));
        d.setLong(k("lt"), ticks);
        d.setLong(k("lp"), packets);
        if (lastT == 0) return;

        long dT = ticks - lastT;
        long dP = packets - lastP;
        if (dT <= 0 || dT > 10) { d.setBuffer(k("bal"), 0); return; } // resumed from a hitch

        double bal = d.buffer(k("bal")) + (dP - dT);
        bal = Math.max(-30.0, Math.min(100.0, bal));
        d.setBuffer(k("bal"), bal);

        if (bal > cfgD("balance", 12.0)) {
            fail(d, player, String.format("client ahead of server clock by %.0f packets", bal));
            d.setBuffer(k("bal"), 0);
        } else if (bal < 0) {
            d.setBuffer(k("bal"), bal + 0.5); // gentle recovery
        }
    }
}
