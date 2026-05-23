package com.koalaguard.engine.checks.combat;

import com.koalaguard.KoalaGuard;
import com.koalaguard.check.CheckCategory;
import com.koalaguard.engine.check.CheckContext;
import com.koalaguard.engine.check.SimCheck;
import com.koalaguard.engine.packet.PacketKind;
import com.koalaguard.engine.state.PositionFrame;
import com.koalaguard.engine.util.Combat;
import org.bukkit.entity.Entity;
import org.bukkit.util.BoundingBox;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Validates the full hit as a state transition, not an event:
 *  1. the reconstructed look ray at the attack tick must actually point at the
 *     victim's reconstructed hitbox (within its real angular size + slack) —
 *     a hit while aimed away is KillAura / Hitbox / rotation-spoof,
 *  2. a corroborating swing animation must exist in the packet stream around
 *     the attack — an attack with no swing in the log is an invalid
 *     interaction chain (NoSwing / packet-only aura).
 * Either contradiction, sustained, is confirmed.
 */
public final class HitValidationCheck extends SimCheck {

    private final Map<UUID, Long> seen = new ConcurrentHashMap<>();

    public HitValidationCheck(KoalaGuard plugin) {
        super(plugin, "hitvalidation", CheckCategory.COMBAT,
                "Hit not supported by reconstructed aim / swing chain");
    }

    @Override
    public void onTick(CheckContext ctx) {
        long atk = ctx.state.combat.lastAttackTick;
        if (atk < 0) return;
        // Dedupe on unique attack-nanos, not the (stationary-frozen) tick.
        long atkNs = ctx.state.combat.lastAttackNanos;
        UUID id = ctx.data.getUuid();
        if (seen.getOrDefault(id, -1L) == atkNs) return;
        seen.put(id, atkNs);
        if (ctx.unstableBasic()) return;

        int vid = ctx.state.combat.lastAttackEntityId;
        Entity victim = Combat.resolveById(ctx.player, vid, 8.0);
        if (victim == null) return;

        PositionFrame f = ctx.state.frameAtOrBefore(atk);
        double[] eye = Combat.eyeLook(f, ctx.player);
        // True rotation at the exact attack-packet instant — survives a
        // stationary attacker whose frame yaw is frozen between movements.
        if (ctx.state.combat.lastAttackHasRot) {
            eye[3] = ctx.state.combat.lastAttackYaw;
            eye[4] = ctx.state.combat.lastAttackPitch;
        }

        // Lag-compensated victim hitbox (rewound to the attack instant). With
        // the accurate box the angular gate is tight enough to catch a small
        // HITBOX-EXPANDER / slightly-off silent aim that the old 9° slack hid.
        double[] box = ctx.state.targets.boxAt(vid, ctx.state.combat.lastAttackNanos);
        double angle, dist, radius;
        if (box != null) {
            angle = Combat.aimAngle(eye[0], eye[1], eye[2],
                    (float) eye[3], (float) eye[4], box);
            dist = Math.max(0.5, Combat.distanceToBox(eye[0], eye[1], eye[2], box));
            radius = Math.max(box[3] - box[0], box[4] - box[1]) / 2.0 + 0.10;
        } else {
            angle = Combat.aimAngle(eye[0], eye[1], eye[2],
                    (float) eye[3], (float) eye[4], victim);
            dist = Math.max(0.5, Combat.distanceToBox(eye[0], eye[1], eye[2], victim));
            BoundingBox b = victim.getBoundingBox();
            radius = Math.max(b.getWidthX(), b.getHeight()) / 2.0 + 0.10;
        }
        double slack = box != null
                ? cfgD("angle-slack-deg", 5.0)                       // accurate
                : cfgD("angle-slack-deg", 5.0) + cfgD("fallback-extra-slack-deg", 6.0);
        double maxAngle = Math.toDegrees(Math.atan2(radius, dist)) + slack;

        // Count swings + attacks in the window for the swing-chain analysis.
        int swingWin = cfgI("swing-window-ticks", 4);
        int swings = 0, attacks = 0;
        long atkNanos = ctx.state.combat.lastAttackNanos;
        long swingWindowNs = swingWin * 50_000_000L;
        for (var pp : ctx.state.log.recent(96)) {
            long age = atkNanos - pp.recvNanos;
            if (age < -swingWindowNs || age > swingWindowNs) continue;
            if (pp.kind == PacketKind.ANIMATION) swings++;
            else if (pp.kind == PacketKind.INTERACT_ENTITY
                    && String.valueOf(pp.objA).contains("ATTACK")) attacks++;
        }
        boolean swung = swings > 0;

        double bad = 0;
        StringBuilder why = new StringBuilder();
        if (angle > maxAngle) {
            bad += (angle - maxAngle) * cfgD("angle-score-scale", 0.6);
            why.append(String.format("aim %.1f° > %.1f° ", angle, maxAngle));
        }
        if (!swung) {
            // NoSwing — USE_ENTITY without any matching ANIMATION packet in
            // the chain. Vanilla: a swing precedes every melee attack.
            bad += cfgD("noswing-score", 5.0);
            why.append("no swing in chain ");
        }
        // FakeSwing — many decoy swings per attack within the window. The old
        // gate "swings >= attacks + 2" FP'd on legit missed clicks (3 swings,
        // 1 land is normal play). Require BOTH a larger absolute swing count
        // AND a high ratio so noise can't trip it. Vanilla rarely exceeds 4
        // swings per actual hit; aura decoy patterns push 6+ per hit.
        int excess = cfgI("fake-swing-excess", 5);
        double minRatio = cfgD("fake-swing-min-ratio", 4.0);
        if (attacks > 0 && swings >= attacks + excess
                && (double) swings / Math.max(1, attacks) >= minRatio) {
            bad += cfgD("fakeswing-score", 4.0);
            why.append(String.format("fake-swing ratio %d:%d ", swings, attacks));
        }

        if (bad > 0) {
            if (diverge(ctx, bad, cfgD("threshold", 9.0), cfgI("min-streak", 4),
                    "invalid hit: " + why.toString().trim(), false)) {
                armCombatCancel(ctx);
            }
        } else {
            clean(ctx, 1.5);
        }
    }

    @Override
    public void cleanup(UUID uuid) {
        super.cleanup(uuid);
        seen.remove(uuid);
    }
}
