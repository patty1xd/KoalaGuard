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
 * AutoWeb — automatic cobweb placement in combat. Two distinct geometric
 * signatures, each on its own gate (web is now flagged whether it's used
 * offensively on the enemy OR defensively on the user themselves):
 *
 *  S0  SELF-WEB without looking down. The legit way to web your own feet
 *      requires looking straight down (pitch ≳ 60°). An AutoWeb cheat fires
 *      while the user keeps the camera level / forward to keep fighting — the
 *      placement is at the user's own position but the reconstructed pitch
 *      is nowhere near the floor. Geometry that no human can produce.
 *
 *  S1  OPPONENT-WEB. A cobweb placed AT (or right on top of) an enemy
 *      player's position. Legit play does not bridge a cobweb into a moving
 *      enemy — that is the offensive autoweb signature. The proximity is
 *      checked against the enemy's REWOUND hitbox via TargetTracker when
 *      available, so the test is desync-tolerant.
 *
 *  S2  POST-ATTACK web. A cobweb placed within a few ticks of an attack on
 *      a nearby player (the place is bound to the fight, not to building).
 *      Adds score on top of S0/S1 — does NOT flag standalone.
 *
 *  S3  MACHINE CADENCE. Near-constant tick interval between consecutive web
 *      placements. CORROBORATES S0/S1; never standalone (a legit fast trapper
 *      can have rhythm).
 *
 * FP-safe: a builder placing webs at a mob farm has no enemy in combat
 * radius (S1 / S2 / S3 dormant), and webbing themselves intentionally
 * involves looking down (S0 dormant). The signals only line up for an
 * automated combat web.
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
        double selfR   = cfgD("self-radius", 1.6);    // place within this of attacker = "on self"
        double enemyR  = cfgD("enemy-radius", 1.6);   // place within this of enemy = "on enemy"
        double minLookDown = cfgD("self-min-look-down-pitch", 55.0);
        double maxOffAngle = cfgD("max-angle", 55.0);

        for (CapturedPacket p : chrono) {
            if (p.kind != PacketKind.BLOCK_PLACE || !p.hasPos) continue;
            if (p.seq > maxSeen) maxSeen = p.seq;
            if (p.seq <= s.lastSeq) continue;
            if (!holdingWeb) continue;

            // True client rotation at the place instant (packet-stamped, not
            // the lagged frame rotation).
            PositionFrame f = ctx.state.frameAtOrBefore(p.tickIndex);
            double[] el = Combat.eyeLook(f, p, ctx.player);
            double ex = el[0], ey = el[1], ez = el[2];
            float yaw = (float) el[3], pitch = (float) el[4];

            double placeCX = p.x + 0.5, placeCY = p.y + 0.5, placeCZ = p.z + 0.5;

            // Attacker's reconstructed position (feet). When no frame exists
            // fall back to the live location — the attacker IS still and the
            // server position matches.
            double ax, ay, az;
            if (f != null) { ax = f.x; ay = f.y; az = f.z; }
            else { var l = ctx.player.getLocation(); ax = l.getX(); ay = l.getY(); az = l.getZ(); }

            double dxSelf = placeCX - ax, dySelf = placeCY - ay, dzSelf = placeCZ - az;
            double distToSelf = Math.sqrt(dxSelf * dxSelf + dySelf * dySelf + dzSelf * dzSelf);

            boolean enemyInCombat = false;
            Player nearestEnemy = null;
            double nearestEnemyDist = Double.MAX_VALUE;
            for (Player o : ctx.player.getWorld().getPlayers()) {
                if (o == ctx.player) continue;
                double dx = o.getLocation().getX() - placeCX;
                double dy = o.getLocation().getY() - placeCY;
                double dz = o.getLocation().getZ() - placeCZ;
                double dSq = dx * dx + dy * dy + dz * dz;
                if (dSq <= combatR * combatR) enemyInCombat = true;
                double d = Math.sqrt(dSq);
                if (d < nearestEnemyDist) { nearestEnemyDist = d; nearestEnemy = o; }
            }

            // ─── S0 — SELF-WEB without looking down ───
            // Cobweb placed at (or right next to) the attacker's own position
            // while their pitch is NOT down. Pitch up to 90 = straight down,
            // so anything below ~55° is "not looking at feet". Gated by an
            // enemy actually being in combat range so a player webbing
            // themselves to escape (which is legit if they look down) AND a
            // builder placing in a void area both pass cleanly.
            if (distToSelf < selfR && enemyInCombat && pitch < minLookDown) {
                bad += cfgD("self-web-score", 7.0);
                why.append(String.format("self-web no look-down (pitch=%.0f° dist=%.2f) ",
                        pitch, distToSelf));
            }

            // ─── S1 — OPPONENT-WEB ───
            // Cobweb placed directly on or alongside an enemy. Uses the
            // rewound enemy hitbox when available so victim desync can't
            // mask the proximity.
            if (nearestEnemy != null) {
                double enemyHitDist = nearestEnemyDist;
                double[] box = ctx.state.targets.boxAt(nearestEnemy.getEntityId(), p.recvNanos);
                if (box != null) {
                    enemyHitDist = Combat.distanceToBox(placeCX, placeCY, placeCZ, box);
                }
                if (enemyHitDist < enemyR) {
                    bad += cfgD("opponent-web-score", 7.0);
                    why.append(String.format("web-on-opponent %s d=%.2f ",
                            nearestEnemy.getName(), enemyHitDist));
                }
            }

            // ─── S2 — generic aim-off web while enemy is in combat range ───
            // Same as the old S0 — kept as a backstop for "place at random
            // spot in the fight that we couldn't classify as self or enemy".
            if (enemyInCombat && distToSelf >= selfR
                    && (nearestEnemy == null || nearestEnemyDist >= enemyR)) {
                double tx = placeCX - ex, ty = placeCY - ey, tz = placeCZ - ez;
                double dist = Math.sqrt(tx * tx + ty * ty + tz * tz);
                if (dist >= 0.1) {
                    Vector look = Combat.lookVector(yaw, pitch);
                    double dot = (look.getX() * tx + look.getY() * ty + look.getZ() * tz) / dist;
                    double angle = Math.toDegrees(Math.acos(MathUtil.clampD(dot, -1, 1)));
                    if (angle > maxOffAngle) {
                        bad += cfgD("aim-score", 5.0);
                        why.append(String.format("aim-off %.0f° ", angle));
                    }
                }
            }

            // ─── S3 — post-attack web (CORROBORATING) ───
            if (enemyInCombat) {
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
            }

            // Cadence sampling for S4.
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

        // ─── S4 — machine cadence (CORROBORATING) ───
        if (bad > 0 && s.intervals.size() >= cfgI("min-samples", 6)) {
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
