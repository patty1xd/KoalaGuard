package com.koalaguard.engine.checks.inventory;

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
 * AutoTotem — dead-simple, decode-independent, continuous.
 *
 * The single fact every AutoTotem cannot avoid: to put a totem in the off hand
 * it must send inventory-click packets, and (Meteor and friends) it sends the
 * pickup+place as 2-3 ClickSlot packets in the SAME tick — a human physically
 * cannot send two inventory clicks 50 ms apart, let alone repeatedly, let
 * alone while fighting. We detect THAT, needing nothing decoded beyond "a
 * window-click happened":
 *
 *  S0  ≥2 CLICK_WINDOW packets within one tick   → primary, self-sufficient.
 *  S1  a CLICK_WINDOW within 250 ms of an entity ATTACK (the inventory GUI
 *      cannot be open while you are attacking)   → covers slow 1-click/tick.
 *  S2  brand / plugin-message advertises autototem.
 *
 * A legit player carrying a totem stack sends NO clicks (stack just
 * decrements) → nothing fires. A legit manual re-equip is ONE slow click with
 * the GUI open (no concurrent attack, not clustered) → nothing fires.
 */
public final class AutoTotemCheck extends SimCheck {

    private static final class S { long lastSeq = Long.MIN_VALUE; }
    private final Map<UUID, S> state = new ConcurrentHashMap<>();

    public AutoTotemCheck(KoalaGuard plugin) {
        super(plugin, "autototem", CheckCategory.COMBAT, "Automated totem equip (click burst)");
    }

    @Override
    public void onTick(CheckContext ctx) {
        UUID id = ctx.data.getUuid();
        S s = state.computeIfAbsent(id, k -> new S());

        if (ctx.data.flagBadBrand) {                                   // S2
            diverge(ctx, cfgD("brand-score", 12.0), cfgD("threshold", 9.0), 1,
                    "client brand advertises autototem: " + ctx.data.packetBrand, false);
        }

        List<CapturedPacket> recent = ctx.state.log.recent(192);
        List<CapturedPacket> chrono = new ArrayList<>(recent);
        java.util.Collections.reverse(chrono);

        long clusterNs = cfgL("cluster-window-ns", 70_000_000L);
        long attackNs  = cfgL("attack-window-ns", 250_000_000L);

        long maxSeen = s.lastSeq;
        double bad = 0;
        StringBuilder why = new StringBuilder();
        int clicksSeen = 0;

        for (CapturedPacket p : chrono) {
            if (p.kind != PacketKind.CLICK_WINDOW) continue;
            clicksSeen++;
            if (p.seq > maxSeen) maxSeen = p.seq;
            if (p.seq <= s.lastSeq) continue;                          // already judged

            int near = 0;
            boolean attacking = false;
            for (CapturedPacket q : chrono) {
                if (q.kind == PacketKind.CLICK_WINDOW
                        && Math.abs(q.recvNanos - p.recvNanos) <= clusterNs) near++;
                if (q.kind == PacketKind.INTERACT_ENTITY
                        && String.valueOf(q.objA).contains("ATTACK")
                        && Math.abs(q.recvNanos - p.recvNanos) <= attackNs) attacking = true;
            }
            if (near >= cfgI("cluster-min", 2)) {                      // S0
                bad += cfgD("cluster-score", 9.0);
                why.append("burst=").append(near).append("clk/tick ");
            }
            if (attacking) {                                           // S1
                bad += cfgD("combat-score", 7.0);
                why.append("inv-click while attacking ");
            }
        }
        s.lastSeq = maxSeen;

        if (debug() && clicksSeen > 0) {
            plugin.getLogger().info("[autototem] " + ctx.player.getName()
                    + " window-clicks visible=" + clicksSeen + " bad=" + bad);
        }

        if (bad > 0) {
            diverge(ctx, bad, cfgD("threshold", 9.0), cfgI("min-streak", 2),
                    "autototem :: " + why.toString().trim(), false);
        } else {
            clean(ctx, 0.4);
        }
    }

    @Override
    public void cleanup(UUID uuid) {
        super.cleanup(uuid);
        state.remove(uuid);
    }
}
