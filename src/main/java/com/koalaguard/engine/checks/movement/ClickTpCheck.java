package com.koalaguard.engine.checks.movement;

import com.koalaguard.KoalaGuard;
import com.koalaguard.check.CheckCategory;
import com.koalaguard.engine.check.CheckContext;
import com.koalaguard.engine.check.SimCheck;
import com.koalaguard.engine.state.PlayerState;
import com.koalaguard.engine.state.PositionFrame;

/**
 * Click-TP / short teleport / flight-warp. A single reconstructed client tick
 * moves further than ANY vanilla state allows (no vehicle/elytra/riptide/
 * knockback/teleport grace active). Unlike PredictionCheck this needs no
 * streak — one impossible jump of several blocks in one tick is conclusive —
 * and it is rubber-banded immediately.
 */
public final class ClickTpCheck extends SimCheck {

    public ClickTpCheck(KoalaGuard plugin) {
        super(plugin, "clicktp", CheckCategory.MOVEMENT, "Impossible single-tick displacement");
    }

    @Override
    public Stage stage() { return Stage.FRAME; }

    private final java.util.Map<java.util.UUID, Long> seenSeq
            = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.Map<java.util.UUID, Long> seenBurst
            = new java.util.concurrent.ConcurrentHashMap<>();

    @Override
    public void onTick(CheckContext ctx) {
        PlayerState s = ctx.state;
        java.util.UUID id = ctx.data.getUuid();

        // PATH C: Meteor ClickTp StatusOnly-burst fingerprint — fires BEFORE
        // the big position packet so the rubber-band can intercept it. Vanilla
        // never sends 3+ rotation/position-less PLAYER_FLYING packets within
        // <40ms each.
        long burstSeq = ctx.data.clickTpBurstSeq;
        Long seenB = seenBurst.get(id);
        if (burstSeq != 0 && (seenB == null || seenB != burstSeq)) {
            seenBurst.put(id, burstSeq);
            diverge(ctx, cfgD("burst-score", 18.0), cfgD("threshold", 10.0),
                    cfgI("burst-min-streak", 1),
                    "clicktp StatusOnly burst: " + ctx.data.clickTpBurstSize
                            + " status-only packets <40ms apart (Meteor pattern)", true);
            return;
        }

        // PATH A: the engine stamped a >8-block free-air teleport that the
        // baseline-reset would otherwise have eaten. Setback unconditionally.
        long tpSeq = ctx.data.clickTpSeq;
        Long seen = seenSeq.get(id);
        if (tpSeq != 0 && (seen == null || seen != tpSeq)) {
            seenSeq.put(id, tpSeq);
            diverge(ctx, cfgD("big-score", 20.0), cfgD("threshold", 10.0),
                    cfgI("big-min-streak", 1),
                    "clicktp: " + ctx.data.clickTpDetail, true);
            return;
        }

        // PATH B: under-8-block fast tick — the frame DID push, evaluate normally.
        PositionFrame f = s.current;
        if (f == null) return;

        boolean liquidGrace = s.exLiquid
                || s.tick - s.lastLiquidTick < cfgI("liquid-grace-ticks", 20);
        if (ctx.unstable() || s.exFlying || s.exVehicle || s.exGliding
                || s.exRiptide || s.exLevitation || s.exClimbing || s.exWeb
                || liquidGrace) {
            clean(ctx, 1.0);
            return;
        }
        long now = System.currentTimeMillis();
        if (now - ctx.data.lastVelocityMs < 1500 || now - ctx.data.lastDamageMs < 1000
                || now - ctx.data.lastTeleportMs < 2000 || now - ctx.data.elytraMs < 2000
                || now - ctx.data.slimeBounceMs < 1500 || now - ctx.data.lastSlimeOrBedMs < 1500
                || now - ctx.data.bubbleColumnMs < 1500) {
            return;
        }

        double maxH = cfgD("max-horizontal", 1.2);     // ~4x sprint, way past any input
        // Vertical is GRAVITY-AWARE and directional. Vanilla free-fall caps at
        // terminal velocity (~3.92 b/t), so a long drop (e.g. off a tall water
        // platform) legitimately reaches large NEGATIVE dy — flagging on
        // |dy| would set the player back on any tall fall. A downward move is
        // only impossible if it EXCEEDS terminal (> max-vertical-down); upward
        // motion past max-vertical-up is a launch/warp no input can produce.
        double maxVUp   = cfgD("max-vertical-up",   1.5);
        double maxVDown = cfgD("max-vertical-down", 4.5);   // terminal + slack
        double h = f.horizontalSpeed();
        boolean vertWarp = f.dy > maxVUp || f.dy < -maxVDown;
        if (h > maxH || vertWarp) {
            diverge(ctx, cfgD("score", 12.0), cfgD("threshold", 10.0),
                    cfgI("min-streak", 1),
                    String.format("teleport dx=%.2f dy=%.2f dz=%.2f", f.dx, f.dy, f.dz),
                    true);
            return;
        }

        // PATH D: gradual / distributed clicktp — single ticks stay sub-1.2
        // but cumulative displacement across a short window (4 ticks ≈ 200ms)
        // exceeds what any vanilla state allows. Net 4-tick horizontal sprint
        // cap is ~1.2 blocks (0.28 * 4) plus knockback slack; >4.0 is far
        // beyond. Only fires outside any kb/teleport grace (gated above).
        int win = cfgI("window-ticks", 4);
        double sumH = 0, sumV = 0;
        int counted = 0;
        for (PositionFrame g : s.recentFrames(win)) {
            sumH += g.horizontalSpeed();
            sumV += Math.abs(g.dy);
            counted++;
        }
        double sumHCap = cfgD("max-window-h", 4.0);
        // 18.0 (was 5.0): four ticks at terminal velocity sum to ~15.7 of
        // vertical travel, so the old cap flagged any long fall. Horizontal
        // (sumH) is the real distributed-clicktp signal; vertical stays only as
        // a sanity backstop above what gravity can ever accumulate.
        double sumVCap = cfgD("max-window-v", 18.0);
        if (counted >= win && (sumH > sumHCap || sumV > sumVCap)) {
            diverge(ctx, cfgD("window-score", 12.0), cfgD("threshold", 10.0),
                    cfgI("window-min-streak", 1),
                    String.format("distributed clicktp: %d-tick sumH=%.2f sumV=%.2f",
                            counted, sumH, sumV),
                    true);
            return;
        }
        clean(ctx, 3.0);
    }

    @Override
    public void cleanup(java.util.UUID uuid) {
        super.cleanup(uuid);
        seenSeq.remove(uuid);
        seenBurst.remove(uuid);
    }
}
