package com.koalaguard.engine.checks.world;

import com.koalaguard.KoalaGuard;
import com.koalaguard.check.CheckCategory;
import com.koalaguard.engine.check.CheckContext;
import com.koalaguard.engine.check.SimCheck;
import com.koalaguard.engine.packet.CapturedPacket;
import com.koalaguard.engine.packet.PacketKind;
import com.koalaguard.engine.state.PositionFrame;
import com.koalaguard.engine.util.Combat;
import com.koalaguard.util.MathUtil;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * AutoWeb — automatic cobweb placement on/around an opponent in combat.
 *
 * AutoWeb fires the moment an enemy is in range: it places a cobweb at the
 * target without the player ever looking at the placement, immediately after
 * an attack, on a machine cadence. Server-side, time-aligned to the EXACT
 * place tick (same reconstruction Reach/Scaffold use):
 *
 *  S0  Aim-off web — a cobweb placed while the reconstructed look points
 *      nowhere near the placed face, with another player in combat range.
 *  S1  Combat-triggered web — a cobweb placed within a few ticks of an attack
 *      on a nearby player (the web is bound to the fight, not to building).
 *  S2  Machine cadence — near-constant tick interval between web placements.
 *
 * FP-safe: a player building/trapping legitimately looks at the block, is not
 * placing it on the exact tick they attack an enemy, and has a human cadence,
 * so none of the signals trip.
 */
public final class AutoWebCheck extends SimCheck {

    private static final class S {
        long lastSeq = Long.MIN_VALUE;
        long lastWebTick = Long.MIN_VALUE;
        final Deque<Long> intervals = new ArrayDeque<>();
    }

    private final Map<UUID, S> state = new ConcurrentHashMap<>();

    public AutoWebCheck(KoalaGuard plugin) {
        super(plugin, "autoweb", CheckCategory.WORLD, "Automated cobweb placement in combat");
    }

    @Override
    public void onTick(CheckContext ctx) {
        if (ctx.unstableBasic()) return;
        UUID id = ctx.data.getUuid();
        S s = state.computeIfAbsent(id, k -> new S());

        // Only cobweb matters; a once-per-tick hand mirror is enough.
        boolean holdingWeb = ctx.state.inv.mainHand == Material.COBWEB
                || ctx.state.inv.offHand == Material.COBWEB;

        List<CapturedPacket> recent = ctx.state.log.recent(96);
        List<CapturedPacket> chrono = new ArrayList<>(recent);
        java.util.Collections.reverse(chrono);

        long maxSeen = s.lastSeq;
        double bad = 0;
        StringBuilder why = new StringBuilder();

        double atkWindow = cfgI("attack-window-ticks", 4);
        double combatR = cfgD("combat-radius", 5.0);

        for (CapturedPacket p : chrono) {
            if (p.kind != PacketKind.BLOCK_PLACE || !p.hasPos) continue;
            if (p.seq > maxSeen) maxSeen = p.seq;
            if (p.seq <= s.lastSeq) continue;
            if (!holdingWeb) continue;

            // Another player within combat range of the placement?
            boolean enemyNear = false;
            for (Player o : ctx.player.getWorld().getPlayers()) {
                if (o == ctx.player) continue;
                if (o.getLocation().distanceSquared(
                        new org.bukkit.Location(ctx.player.getWorld(),
                                p.x + 0.5, p.y + 0.5, p.z + 0.5)) <= combatR * combatR) {
                    enemyNear = true; break;
                }
            }
            if (!enemyNear) continue;

            PositionFrame f = ctx.state.frameAtOrBefore(p.tickIndex);
            double[] el = Combat.eyeLook(f, ctx.player);
            double ex = el[0], ey = el[1], ez = el[2];
            Vector look = p.hasRot
                    ? Combat.lookVector(p.yaw, p.pitch)
                    : Combat.lookVector((float) el[3], (float) el[4]);

            double tx = p.x + 0.5 - ex, ty = p.y + 0.5 - ey, tz = p.z + 0.5 - ez;
            double dist = Math.sqrt(tx * tx + ty * ty + tz * tz);
            if (dist < 0.1) continue;
            double dot = (look.getX() * tx + look.getY() * ty + look.getZ() * tz) / dist;
            double angle = Math.toDegrees(Math.acos(MathUtil.clampD(dot, -1, 1)));

            if (angle > cfgD("max-angle", 55.0)) {                       // S0
                bad += cfgD("aim-score", 6.0);
                why.append(String.format("web aim-off %.0f° ", angle));
            }

            // S1 — placed right after an attack on someone.
            boolean afterAttack = false;
            for (CapturedPacket q : recent) {
                if (q.kind != PacketKind.INTERACT_ENTITY) continue;
                if (!String.valueOf(q.objA).contains("ATTACK")) continue;
                long dt = p.tickIndex - q.tickIndex;
                if (dt >= 0 && dt <= atkWindow) { afterAttack = true; break; }
            }
            if (afterAttack) {
                bad += cfgD("combat-score", 5.0);
                why.append("post-attack web ");
            }

            if (s.lastWebTick != Long.MIN_VALUE) {
                long iv = p.tickIndex - s.lastWebTick;
                if (iv > 0 && iv < 200) {
                    s.intervals.addLast(iv);
                    while (s.intervals.size() > cfgI("sample-cap", 20)) s.intervals.removeFirst();
                }
            }
            s.lastWebTick = p.tickIndex;
        }
        s.lastSeq = maxSeen;

        if (s.intervals.size() >= cfgI("min-samples", 6)) {              // S2
            double sd = MathUtil.standardDeviation(s.intervals);
            if (sd < cfgD("max-sd-interval", 0.9)) {
                bad += cfgD("cadence-score", 6.0);
                why.append(String.format("machine cadence sd=%.2f n=%d ",
                        sd, s.intervals.size()));
            }
        }

        if (bad > 0) {
            if (diverge(ctx, bad, cfgD("threshold", 10.0), cfgI("min-streak", 3),
                    "autoweb :: " + why.toString().trim(), false)) {
                armCombatCancel(ctx);
            }
        } else {
            clean(ctx, 0.5);
        }
    }

    @Override
    public void cleanup(UUID uuid) {
        super.cleanup(uuid);
        state.remove(uuid);
    }
}
