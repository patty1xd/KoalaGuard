package com.koalaguard.engine.checks.combat;

import com.koalaguard.KoalaGuard;
import com.koalaguard.check.CheckCategory;
import com.koalaguard.engine.check.CheckContext;
import com.koalaguard.engine.check.SimCheck;
import com.koalaguard.engine.packet.CapturedPacket;
import com.koalaguard.engine.packet.PacketKind;
import com.koalaguard.engine.util.Combat;
import com.koalaguard.util.MathUtil;
import org.bukkit.Material;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * BowAimbot / TridentAimbot — projectile-aim aurabot detection.
 *
 * Cheat packet sequence: USE_ITEM (bow/crossbow/trident) starts the draw →
 * rotation packets while charging → RELEASE_USE_ITEM (PLAYER_DIGGING with
 * action RELEASE_USE_ITEM) at a calculated angle onto the target. Common
 * features:
 *   • Snap-on-release: rotation barely moves during draw, then snaps to
 *     target at the moment of release — vanilla flick + aim is a smooth
 *     accumulation, not a single-tick snap.
 *   • Predictive-intercept: rotation at release is the calculated lead
 *     angle for the target's velocity — over many shots the angle-to-
 *     nearest-enemy converges with low variance, while a human's misses
 *     have a much wider spread (real archers miss).
 *
 * Detection signals (both required for confirmation):
 *  S0  Snap-at-release: yaw delta in the LAST tick of the draw is much
 *      larger than the running mean during the draw — a sudden shift onto
 *      the target right before release.
 *  S1  Inhuman target-aim consistency: across many releases the angle
 *      between the release-time look ray and the nearest living entity
 *      stays under {@code max-mean-deg} with low SD.
 *
 * FP-safe: human bow flicks ARE quick but show drift in the milliseconds
 * around release (release timing varies, finger isn't tick-perfect). Bot
 * snap is single-tick + immediate release.
 */
public final class BowAimbotCheck extends SimCheck {

    private static final class S {
        long lastReleaseSeq = Long.MIN_VALUE;
        long useStartNanos  = Long.MIN_VALUE;
        final Deque<Double> releaseAngles = new ArrayDeque<>();
        final Deque<Double> predictAngles = new ArrayDeque<>();
        int snapHits;
    }

    private final Map<UUID, S> state = new ConcurrentHashMap<>();

    public BowAimbotCheck(KoalaGuard plugin) {
        super(plugin, "bowaimbot", CheckCategory.COMBAT,
                "Bow / trident projectile aimbot (snap-on-release)");
    }

    @Override
    public void onTick(CheckContext ctx) {
        if (ctx.unstableBasic()) return;
        UUID id = ctx.data.getUuid();
        S s = state.computeIfAbsent(id, k -> new S());

        // Track the latest USE_ITEM start (bow/crossbow/trident draw start).
        if (ctx.state.usingItem) {
            Material h = ctx.state.inv.mainHand;
            Material o = ctx.state.inv.offHand;
            boolean drawing = isDrawable(h) || isDrawable(o);
            if (drawing && ctx.state.usingItemSinceNanos > s.useStartNanos) {
                s.useStartNanos = ctx.state.usingItemSinceNanos;
            }
        }

        // Find RELEASE_USE_ITEM packets (PLAYER_DIGGING with strA matching).
        for (CapturedPacket p : ctx.state.log.recent(96)) {
            if (p.kind != PacketKind.DIGGING) continue;
            if (!"RELEASE_USE_ITEM".equals(p.strA)) continue;
            if (p.seq <= s.lastReleaseSeq) continue;
            s.lastReleaseSeq = p.seq;
            // Must have a recent draw start of the right item type.
            if (s.useStartNanos == Long.MIN_VALUE) continue;
            long drawDurMs = (p.recvNanos - s.useStartNanos) / 1_000_000L;
            if (drawDurMs < cfgL("min-draw-ms", 80L)
                    || drawDurMs > cfgL("max-draw-ms", 10_000L)) continue;

            // Reconstructed look at release. p.hasRot is true (we stamp all
            // DIGGING packets... actually no — we stamp INTERACT_ENTITY +
            // ANIMATION + BLOCK_PLACE. RELEASE_USE_ITEM is DIGGING. Fall
            // back to netYaw/netPitch (latest known client rotation, set on
            // every rotation packet on the netty thread).
            float relYaw   = p.hasRot ? p.yaw   : ctx.state.netYaw;
            float relPitch = p.hasRot ? p.pitch : ctx.state.netPitch;

            // S0 — snap-on-release: yaw delta in the LAST recorded rotation
            // delta is large vs the mean over the draw window.
            java.util.List<Float> yawDeltas = ctx.state.signedYawDeltas(8);
            if (!yawDeltas.isEmpty()) {
                double last = Math.abs(yawDeltas.get(yawDeltas.size() - 1));
                double mean = 0;
                int n = 0;
                for (int i = 0; i < yawDeltas.size() - 1; i++) {
                    mean += Math.abs(yawDeltas.get(i)); n++;
                }
                mean = n > 0 ? mean / n : 0;
                if (last > cfgD("snap-min-deg", 18.0) && last > mean * cfgD("snap-vs-mean-ratio", 4.0)) {
                    s.snapHits++;
                }
            }

            // S1 — angle to nearest PLAYER enemy at release. The old loop
            // iterated EVERY LivingEntity including pets/villagers/chickens
            // at the shooter's feet, which sank bestAng to ~0 regardless of
            // intent and FP'd anyone fighting in a populated area. Bows in
            // PvP target players; restrict the sample set to players in a
            // different scoreboard team / non-pet relationship.
            Player attacker = ctx.player;
            org.bukkit.Location l = attacker.getEyeLocation();
            double bestAng = Double.MAX_VALUE;
            double bestPredictAng = Double.MAX_VALUE;
            Vector look = Combat.lookVector(relYaw, relPitch);
            // Arrow muzzle speed (blocks/sec) — vanilla full-draw bow ≈ 60.
            double arrowSpeed = cfgD("arrow-speed", 60.0);
            for (org.bukkit.entity.Entity e :
                    attacker.getWorld().getNearbyEntities(l, 60, 60, 60)) {
                if (!(e instanceof Player)) continue;            // PvP only
                if (e == attacker) continue;
                Player pl = (Player) e;
                if (pl.getGameMode() == org.bukkit.GameMode.SPECTATOR
                        || pl.getGameMode() == org.bukkit.GameMode.CREATIVE) continue;
                double cx = e.getBoundingBox().getCenterX();
                double cy = e.getBoundingBox().getCenterY();
                double cz = e.getBoundingBox().getCenterZ();
                Vector to = new Vector(cx - l.getX(), cy - l.getY(), cz - l.getZ());
                if (to.lengthSquared() < 1e-7) continue;
                double dist = to.length();
                Vector toN = to.clone().normalize();
                double dot = Math.max(-1.0, Math.min(1.0, look.dot(toN)));
                double ang = Math.toDegrees(Math.acos(dot));
                if (ang < bestAng) bestAng = ang;

                // Predicted-intercept angle: where will the victim be when
                // the arrow arrives? Bot aim is mathematically locked onto
                // the lead vector; humans don't compute it.
                Vector vel = pl.getVelocity();
                double ttkSec = dist / Math.max(1.0, arrowSpeed);
                double px = cx + vel.getX() * ttkSec * 20.0;
                double py = cy + vel.getY() * ttkSec * 20.0;
                double pz = cz + vel.getZ() * ttkSec * 20.0;
                Vector toP = new Vector(px - l.getX(), py - l.getY(), pz - l.getZ());
                if (toP.lengthSquared() > 1e-7) {
                    toP.normalize();
                    double pdot = Math.max(-1.0, Math.min(1.0, look.dot(toP)));
                    double pang = Math.toDegrees(Math.acos(pdot));
                    if (pang < bestPredictAng) bestPredictAng = pang;
                }
            }
            if (bestAng < 90.0) {                       // someone was in front
                s.releaseAngles.addLast(bestAng);
                while (s.releaseAngles.size() > cfgI("sample-cap", 12))
                    s.releaseAngles.removeFirst();
            }
            if (bestPredictAng < 90.0) {
                s.predictAngles.addLast(bestPredictAng);
                while (s.predictAngles.size() > cfgI("sample-cap", 12))
                    s.predictAngles.removeFirst();
            }
            // Reset the draw cursor so a follow-up release needs a fresh USE.
            s.useStartNanos = Long.MIN_VALUE;
        }

        // ─── Evaluate ───
        int minSamples = cfgI("min-samples", 5);
        if (s.releaseAngles.size() < minSamples) return;

        double mean = MathUtil.average(s.releaseAngles);
        double sd   = MathUtil.standardDeviation(s.releaseAngles);
        double maxMean = cfgD("max-mean-deg", 4.0);
        double maxSd   = cfgD("max-sd-deg",   2.5);

        boolean inhumanAccuracy = mean < maxMean && sd < maxSd;
        boolean snappy = s.snapHits >= cfgI("min-snap-hits", 3);

        // Predictive-intercept fingerprint: bot's lead-aim residuals cluster
        // tighter than its naive-aim residuals (the bot is aiming where the
        // victim WILL be). If predict residuals are < 1.5° mean and lower
        // than naive residuals by a clear margin, the player is computing
        // the lead vector — which a human cannot do.
        boolean leading = false;
        if (s.predictAngles.size() >= cfgI("min-samples", 5)) {
            double pMean = MathUtil.average(s.predictAngles);
            double pSd   = MathUtil.standardDeviation(s.predictAngles);
            leading = pMean < cfgD("predict-max-mean-deg", 1.5)
                    && pSd < cfgD("predict-max-sd-deg", 1.2)
                    && pMean < mean - cfgD("predict-vs-naive-edge", 0.5);
        }

        if ((inhumanAccuracy && snappy) || leading) {
            diverge(ctx, cfgD("score", 7.0), cfgD("threshold", 9.0),
                    cfgI("min-streak", 2),
                    String.format("bow aimbot: mean=%.2f° sd=%.2f° snaps=%d n=%d%s",
                            mean, sd, s.snapHits, s.releaseAngles.size(),
                            leading ? " +lead-lock" : ""),
                    false);
        } else if (inhumanAccuracy || snappy) {
            // Half-signal — bleed up gently.
            clean(ctx, 0.2);
        } else {
            clean(ctx, 1.0);
        }
    }

    private static boolean isDrawable(Material m) {
        return m == Material.BOW || m == Material.CROSSBOW || m == Material.TRIDENT;
    }

    @Override
    public void cleanup(UUID uuid) {
        super.cleanup(uuid);
        state.remove(uuid);
    }
}
