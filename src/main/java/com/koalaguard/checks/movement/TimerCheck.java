package com.koalaguard.checks.movement;

import com.koalaguard.KoalaGuard;
import com.koalaguard.check.MovementCheck;
import com.koalaguard.data.PlayerData;
import org.bukkit.entity.Player;

/**
 * Timer / game-speed — counts the player's REAL movement packets per second
 * (captured on the netty thread). Vanilla sends ~20/s whether moving or
 * idle; a timer raises that. A leaking balance only accumulates when the
 * rate is consistently above vanilla, so a lag burst (which lowers the rate)
 * can never false-positive.
 */
public final class TimerCheck extends MovementCheck {

    public TimerCheck(KoalaGuard plugin) {
        super(plugin, "timer", "Sending movement packets faster than real time");
    }

    @Override
    public void handle(PlayerData d, Player player) {
        if (d.exemptVehicle || d.exemptGliding || d.exemptRiptide) { d.setBuffer(k("bal"), 0); return; }
        long now = System.currentTimeMillis();
        long cnt = d.flyingTimes.stream().filter(t -> now - t <= 1000).count();
        if (cnt < 5) { d.setBuffer(k("bal"), 0); return; } // not enough data / loading

        double expected = 20.0;
        double bal = d.buffer(k("bal"));
        if (cnt > expected + 2) bal = Math.min(60.0, bal + (cnt - expected) * 0.5);
        else bal = Math.max(0.0, bal - 1.5);
        d.setBuffer(k("bal"), bal);

        if (bal > cfgD("balance", 9.0)) {
            fail(d, player, String.format("%d packets/s (vanilla ~20)", cnt));
            d.setBuffer(k("bal"), 0);
        }
    }
}
