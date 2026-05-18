package com.koalaguard.engine.checks.combat;

import com.koalaguard.KoalaGuard;
import com.koalaguard.check.CheckCategory;
import com.koalaguard.engine.check.CheckContext;
import com.koalaguard.engine.check.SimCheck;
import com.koalaguard.engine.state.PositionFrame;
import org.bukkit.Material;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Mace smash exploit ("MaceKill" / "MaceDamage"). The 1.21 mace deals a huge
 * bonus only when the attacker is genuinely FALLING — the server scales the
 * smash by the attacker's fall. The cheat keeps a normal grounded position but
 * tells the server it is airborne / falling (spoofed onGround / fake fall) so
 * the server grants the smash bonus with no real fall.
 *
 * Packet-only, server-authoritative: with a MACE in hand, if the client CLAIMED
 * it was airborne around the hit while server collision says it was supported
 * the whole time AND there was no genuine descent, the smash was faked. A legit
 * mace smash has a real airborne arc + real downward velocity → never flags. A
 * normal grounded mace hit never claims air → never flags.
 */
public final class MaceCheck extends SimCheck {

    private final Map<UUID, Long> seen = new ConcurrentHashMap<>();

    public MaceCheck(KoalaGuard plugin) {
        super(plugin, "mace", CheckCategory.COMBAT, "Faked mace smash (no real fall)");
    }

    @Override
    public void onTick(CheckContext ctx) {
        long atk = ctx.state.combat.lastAttackTick;
        if (atk < 0) return;
        UUID id = ctx.data.getUuid();
        if (seen.getOrDefault(id, -1L) == atk) return;
        seen.put(id, atk);
        if (ctx.unstableBasic()) return;
        if (ctx.state.inv.mainHand != Material.MACE) return;          // mace only
        if (ctx.state.exVehicle || ctx.state.exClimbing || ctx.state.exLiquid
                || ctx.state.exLevitation || ctx.state.exGliding || ctx.state.exWeb
                || ctx.state.exRiptide) return;

        int win = cfgI("window-ticks", 6);
        boolean claimedAir = false, leftGround = false, haveFrames = false;
        double maxDown = 0;
        for (PositionFrame f : ctx.state.recentFrames(16)) {
            if (f.tick > atk || f.tick < atk - win) continue;
            haveFrames = true;
            maxDown = Math.min(maxDown, f.dy);
            if (!f.clientGround) claimedAir = true;     // client said "airborne"
            if (!f.simGround) leftGround = true;        // server agrees airborne
        }
        if (!haveFrames) return;

        // Faked smash: client advertised airborne/falling to earn the bonus,
        // but server geometry shows it was supported throughout AND there was
        // no genuine downward velocity (a real smash has a true fall arc).
        boolean realFall = maxDown < -cfgD("min-fall-dy", 0.25);
        boolean faked = claimedAir && !leftGround && !realFall;

        if (faked) {
            if (diverge(ctx, cfgD("score", 6.0), cfgD("threshold", 10.0),
                    cfgI("min-streak", 3),
                    String.format("mace smash with no real fall (claimedAir, simGround, maxDown=%.3f)",
                            maxDown), false)) {
                armCombatCancel(ctx);
            }
        } else {
            clean(ctx, 2.0);
        }
    }

    @Override
    public void cleanup(UUID uuid) {
        super.cleanup(uuid);
        seen.remove(uuid);
    }
}
