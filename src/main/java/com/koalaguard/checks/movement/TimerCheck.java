package com.koalaguard.checks.movement;

import com.koalaguard.KoalaGuard;
import com.koalaguard.check.MovementCheck;
import com.koalaguard.data.PlayerData;
import org.bukkit.entity.Player;

/**
 * Timer / game-speed detection.
 *
 * The client sends ~20 position updates per second (one per tick). Timer
 * hacks accelerate the client clock so more updates arrive per real second,
 * letting the player move and attack faster. We use a leaking-balance model:
 * each position packet credits one tick of "time"; if the player banks far
 * more ticks than real time allows, they are running a timer.
 */
public final class TimerCheck extends MovementCheck {

    public TimerCheck(KoalaGuard plugin) {
        super(plugin, "timer", "Sending movement packets faster than real time");
    }

    @Override
    public void handle(PlayerData data, Player player) {
        if (data.exemptVehicle || data.exemptRiptide || data.exemptGliding) return;
        long now = System.currentTimeMillis();
        long last = data.getLong(k("last"));
        data.setLong(k("last"), now);
        if (last == 0) return;

        long interval = now - last;
        if (interval > 150) {                  // gap (lag/AFK) — reset balance
            data.setBuffer(k("bal"), 0);
            return;
        }

        // Expected 50 ms per packet. Negative interval-deficit banks "extra"
        // ticks. Drift accumulates only when consistently early.
        double balance = data.buffer(k("bal"));
        balance += (50.0 - interval);
        balance = Math.max(-200.0, Math.min(2000.0, balance));
        data.setBuffer(k("bal"), balance);

        double trigger = cfgD("balance-ms", 320.0);
        if (balance > trigger) {
            fail(data, player, String.format("clock drift %.0fms ahead", balance));
            data.setBuffer(k("bal"), 0);
        }
    }
}
