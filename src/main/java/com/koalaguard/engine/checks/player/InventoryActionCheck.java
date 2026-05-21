package com.koalaguard.engine.checks.player;

import com.koalaguard.KoalaGuard;
import com.koalaguard.check.CheckCategory;
import com.koalaguard.engine.check.CheckContext;
import com.koalaguard.engine.check.SimCheck;
import com.koalaguard.engine.packet.CapturedPacket;
import com.koalaguard.engine.packet.PacketKind;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Generalised inventory automation (AutoArmor, AutoWeapon, ChestStealer,
 * Quiver, AutoTool, AutoReplenish, AutoMend …). Same impossible-sequence logic
 * as AutoTotem but for ANY player-inventory slot: a window-0 ClickSlot while
 * the player is attacking (GUI cannot be open) or ≥2 window-0 clicks inside
 * one tick. Slot 45 is left to AutoTotemCheck (no double flag).
 */
public final class InventoryActionCheck extends SimCheck {

    private static final class S { long lastSeq = Long.MIN_VALUE; }
    private final Map<UUID, S> state = new ConcurrentHashMap<>();

    public InventoryActionCheck(KoalaGuard plugin) {
        super(plugin, "inventoryaction", CheckCategory.PLAYER,
                "Inventory action with no open screen");
    }

    @Override
    public void onTick(CheckContext ctx) {
        if (ctx.unstableBasic()) return;
        UUID id = ctx.data.getUuid();
        S s = state.computeIfAbsent(id, k -> new S());

        List<CapturedPacket> recent = ctx.state.log.recent(160);
        List<CapturedPacket> chrono = new ArrayList<>(recent);
        java.util.Collections.reverse(chrono);

        long clusterNs = cfgL("cluster-window-ns", 60_000_000L);
        long attackNs  = cfgL("attack-window-ns", 250_000_000L);
        long chestNs   = cfgL("chest-burst-window-ns", 250_000_000L);
        long maxSeen = s.lastSeq;
        double bad = 0;
        StringBuilder why = new StringBuilder();

        for (CapturedPacket p : chrono) {
            if (p.kind != PacketKind.CLICK_WINDOW) continue;
            if (p.seq > maxSeen) maxSeen = p.seq;
            if (p.seq <= s.lastSeq) continue;

            // ── Variant A: AutoArmor / AutoTool / quick-restock — window 0
            //    (player inventory) clicks clustered or coincident with an
            //    attack. The cheat moves items into hotbar/armor slots without
            //    the player ever opening an inventory screen.
            if (p.intB == 0) {
                if (p.intA == 45) continue;             // totem → AutoTotemCheck
                int near = 0;
                boolean attacking = false;
                for (CapturedPacket q : chrono) {
                    if (q.kind == PacketKind.CLICK_WINDOW && q.intB == 0
                            && Math.abs(q.recvNanos - p.recvNanos) <= clusterNs) near++;
                    if (q.kind == PacketKind.INTERACT_ENTITY
                            && String.valueOf(q.objA).contains("ATTACK")
                            && Math.abs(q.recvNanos - p.recvNanos) <= attackNs) attacking = true;
                }
                if (near >= 2) {
                    bad += cfgD("cluster-score", 8.0);
                    why.append("clustered ").append(near).append("clk ");
                }
                if (attacking) {
                    bad += cfgD("combat-score", 8.0);
                    why.append("inv-click while attacking ");
                }
                continue;
            }

            // ── Variant B: ChestStealer / FastStealer / AutoSteal — non-zero
            //    window (chest, shulker, double-chest, donkey, dispenser, etc.)
            //    with many ClickWindow packets of the SAME window-id inside a
            //    single tick. Vanilla shift-click cap: one slot per tick at
            //    most; a stealer fires 10+ in 100ms. SerenityCE / Wurst
            //    AutoSteal / CheataClient / XIV / CardeJIbka mod all share this
            //    wire signature regardless of which item they target.
            int sameWin = 0;
            for (CapturedPacket q : chrono) {
                if (q.kind != PacketKind.CLICK_WINDOW) continue;
                if (q.intB != p.intB) continue;
                if (Math.abs(q.recvNanos - p.recvNanos) <= chestNs) sameWin++;
            }
            int chestThresh = cfgI("chest-burst-threshold", 6);
            if (sameWin >= chestThresh) {
                bad += (sameWin - chestThresh + 1) * cfgD("chest-score-scale", 2.0);
                why.append(sameWin).append(" clicks in window ").append(p.intB).append(" ");
            }
        }
        s.lastSeq = maxSeen;

        if (bad > 0) {
            diverge(ctx, bad, cfgD("threshold", 10.0), cfgI("min-streak", 3),
                    "inventory automation :: " + why.toString().trim(), false);
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
