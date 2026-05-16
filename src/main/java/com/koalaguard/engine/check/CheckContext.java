package com.koalaguard.engine.check;

import com.koalaguard.KoalaGuard;
import com.koalaguard.data.PlayerData;
import com.koalaguard.engine.lag.LagCompensator;
import com.koalaguard.engine.sim.SimResult;
import com.koalaguard.engine.state.PlayerState;
import org.bukkit.entity.Player;

/**
 * Everything a check needs for ONE tick, assembled by the engine after the
 * state machine advanced and the simulator ran. Checks are stateless w.r.t.
 * this object — their only persistent state is their own ViolationScore.
 */
public final class CheckContext {

    public final KoalaGuard plugin;
    public final PlayerData data;
    public final Player player;
    public final PlayerState state;
    public final SimResult sim;
    public final long tick;

    public CheckContext(KoalaGuard plugin, PlayerData data, Player player,
                        PlayerState state, SimResult sim, long tick) {
        this.plugin = plugin;
        this.data = data;
        this.player = player;
        this.state = state;
        this.sim = sim;
        this.tick = tick;
    }

    public LagCompensator lag() { return data.lag; }

    /** True while the world/connection makes reconstruction untrustworthy. */
    public boolean unstable() {
        if (data.lag.unstable()) return true;
        return plugin.getSafetyManager().shouldSuppress(data, player);
    }

    /** Damage-triggered checks must not honour the damage/velocity grace. */
    public boolean unstableBasic() {
        if (data.lag.unstable()) return true;
        return plugin.getSafetyManager().shouldSuppressBasic(data, player);
    }
}
