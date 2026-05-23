package com.koalaguard.engine.checks.world;

import com.koalaguard.KoalaGuard;
import com.koalaguard.check.CheckCategory;
import com.koalaguard.engine.check.CheckContext;
import com.koalaguard.engine.check.SimCheck;
import com.koalaguard.engine.state.PlayerState;
import com.koalaguard.util.MathUtil;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * AutoWeb — automatic cobweb placement in combat.
 *
 * Data source: the SERVER-AUTHORITATIVE block-place ring filled by
 * BukkitStateListener.onBlockPlace at MONITOR. The previous version read
 * {@code inv.mainHand} from the once-per-tick inventory mirror, which a
 * swap-in / place / swap-back cheat completes inside ONE client tick — so
 * the mirror snapshot showed the weapon, not the cobweb, and the cheat
 * silently bypassed every check that gated on "holdingWeb". The place ring
 * captures the ACTUAL placed material plus the player's location+pitch at
 * the place instant, regardless of whether the cheat swapped back.
 *
 * Two distinct geometric signatures, both gated on cobweb specifically:
 *
 *  S0  SELF-WEB without looking down. Cobweb placed within {@code self-radius}
 *      of the attacker's own position WHILE their pitch is not steeply down.
 *      Legit self-webbing requires looking at your feet (pitch ≳ 55°). The
 *      cheat keeps the camera level to keep fighting forward — that geometry
 *      no human produces.
 *
 *  S1  OPPONENT-WEB. Cobweb placed on or adjacent to an enemy player's
 *      hitbox (rewound via TargetTracker when available so victim desync
 *      can't mask the proximity).
 *
 * Both S0 and S1 require an enemy in {@code combat-radius} of the attacker
 * so a non-combat web is never flagged; cadence + post-attack corroborate
 * only (never standalone).
 */
public final class AutoWebCheck extends SimCheck {

    private static final class S {
        long lastSeenPlaceNanos = Long.MIN_VALUE;
        long lastWebNanos = Long.MIN_VALUE;
        final Deque<Long> intervalsMs = new ArrayDeque<>();
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

        double combatR  = cfgD("combat-radius", 5.0);
        double selfR    = cfgD("self-radius", 1.6);
        double enemyR   = cfgD("enemy-radius", 1.6);
        double minLookDown = cfgD("self-min-look-down-pitch", 55.0);
        long postAtkWindowNs = cfgL("post-attack-window-ns", 400_000_000L);

        double bad = 0;
        StringBuilder why = new StringBuilder();
        long maxSeen = s.lastSeenPlaceNanos;

        List<PlayerState.PlaceRecord> places = ctx.state.recentPlaces(20);
        for (PlayerState.PlaceRecord r : places) {
            if (r.nanos > maxSeen) maxSeen = r.nanos;
            if (r.nanos <= s.lastSeenPlaceNanos) continue;
            if (r.material != Material.COBWEB) continue;

            double placeCX = r.bx + 0.5;
            double placeCY = r.by + 0.5;
            double placeCZ = r.bz + 0.5;

            // Attacker position at the place instant (captured by the event).
            double ax = r.px, ay = r.py, az = r.pz;
            float pitch = r.pPitch;

            double dxSelf = placeCX - ax, dySelf = placeCY - ay, dzSelf = placeCZ - az;
            double distToSelf = Math.sqrt(dxSelf * dxSelf + dySelf * dySelf + dzSelf * dzSelf);

            // Find enemy proximity to the placement (combat-gate + S1 target).
            boolean enemyInCombat = false;
            Player nearestEnemy = null;
            double nearestEnemyDist = Double.MAX_VALUE;
            for (Player o : ctx.player.getWorld().getPlayers()) {
                if (o == ctx.player) continue;
                if (o.getGameMode() == org.bukkit.GameMode.SPECTATOR
                        || o.getGameMode() == org.bukkit.GameMode.CREATIVE) continue;
                double dx = o.getLocation().getX() - placeCX;
                double dy = o.getLocation().getY() - placeCY;
                double dz = o.getLocation().getZ() - placeCZ;
                double dSq = dx * dx + dy * dy + dz * dz;
                if (dSq <= combatR * combatR) enemyInCombat = true;
                double d = Math.sqrt(dSq);
                if (d < nearestEnemyDist) { nearestEnemyDist = d; nearestEnemy = o; }
            }
            if (!enemyInCombat) continue;        // non-combat web — not the cheat

            // ─── S0 — SELF-WEB without looking down ───
            if (distToSelf < selfR && pitch < minLookDown) {
                bad += cfgD("self-web-score", 7.0);
                why.append(String.format("self-web no look-down (pitch=%.0f° dist=%.2f) ",
                        pitch, distToSelf));
            }

            // ─── S1 — OPPONENT-WEB (cobweb at enemy hitbox) ───
            if (nearestEnemy != null) {
                double enemyHitDist = nearestEnemyDist;
                double[] box = ctx.state.targets.boxAt(nearestEnemy.getEntityId(), r.nanos);
                if (box != null) {
                    // closest point on the rewound box to the placement centre
                    double dx = Math.max(Math.max(box[0] - placeCX, 0), placeCX - box[3]);
                    double dy = Math.max(Math.max(box[1] - placeCY, 0), placeCY - box[4]);
                    double dz = Math.max(Math.max(box[2] - placeCZ, 0), placeCZ - box[5]);
                    enemyHitDist = Math.sqrt(dx * dx + dy * dy + dz * dz);
                }
                if (enemyHitDist < enemyR) {
                    bad += cfgD("opponent-web-score", 7.0);
                    why.append(String.format("web-on-opponent %s d=%.2f ",
                            nearestEnemy.getName(), enemyHitDist));
                }
            }

            // ─── S2 — post-attack web (CORROBORATING) ───
            // Within ~400ms of the attacker's last attack-entity packet.
            long sinceAtkNs = r.nanos - ctx.state.combat.lastAttackNanos;
            if (sinceAtkNs >= 0 && sinceAtkNs <= postAtkWindowNs) {
                bad += cfgD("combat-score", 5.0);
                why.append("post-attack web ");
            }

            // Cadence sample (only counted when a real S0/S1 hit fired).
            if (s.lastWebNanos != Long.MIN_VALUE) {
                long ivMs = (r.nanos - s.lastWebNanos) / 1_000_000L;
                if (ivMs > 0 && ivMs < 5000) {
                    s.intervalsMs.addLast(ivMs);
                    while (s.intervalsMs.size() > cfgI("sample-cap", 20))
                        s.intervalsMs.removeFirst();
                }
            }
            s.lastWebNanos = r.nanos;
        }
        s.lastSeenPlaceNanos = maxSeen;

        // ─── S3 — machine cadence (CORROBORATING) ───
        if (bad > 0 && s.intervalsMs.size() >= cfgI("min-samples", 6)) {
            double sd = MathUtil.standardDeviation(s.intervalsMs);
            if (sd < cfgD("max-sd-interval-ms", 35.0)) {
                bad += cfgD("cadence-score", 6.0);
                why.append(String.format("machine cadence sd=%.0fms n=%d ",
                        sd, s.intervalsMs.size()));
            }
        }

        if (bad > 0) {
            if (diverge(ctx, bad, cfgD("threshold", 10.0), cfgI("min-streak", 2),
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
