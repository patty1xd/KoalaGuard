package com.koalaguard.engine.checks.world;

import com.koalaguard.KoalaGuard;
import com.koalaguard.check.CheckCategory;
import com.koalaguard.engine.check.CheckContext;
import com.koalaguard.engine.check.SimCheck;
import com.koalaguard.engine.packet.CapturedPacket;
import com.koalaguard.engine.packet.PacketKind;
import org.bukkit.World;
import org.bukkit.block.Block;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * AirPlace / MidAir / NoSupport block placement.
 *
 * Vanilla requires the block-place packet to identify a CLICKED face on an
 * existing block: that block must be solid (or otherwise interactable) AND
 * adjacent to where the new block will go. Cheats (Wurst AirPlace,
 * LiquidBounce BlockPlace variants, ForgeHax MidAirPlace, Meteor BlockTweaks)
 * forge the packet so the "clicked block" position points to AIR — the
 * resulting placement floats in mid-air with no support.
 *
 * Detection is server-authoritative: at the BLOCK_PLACE packet's recorded
 * clicked-block position, ask the world whether that block is collidable. If
 * not, the packet's clicked-face is fake. A short tolerance handles the
 * legitimate sub-tick "break + replace" race (MC-279849) by checking whether
 * a START_DIGGING for that exact position occurred just before the place.
 *
 * Covers:
 *   • AirPlace (Wurst / LiquidBounce / ForgeHax / Meteor) — fake clicked face.
 *   • RangePlace (Wurst "Range" setting up to 5+ blocks) — also caught when the
 *     placement face is air, OR by ScaffoldCheck's distance gate.
 *   • Block-replace tower (place into your own feet position) — clicked face
 *     is the feet block, which is the player's own legs (air) at place time.
 */
public final class AirPlaceCheck extends SimCheck {

    private final Map<UUID, Long> seen = new ConcurrentHashMap<>();

    public AirPlaceCheck(KoalaGuard plugin) {
        super(plugin, "airplace", CheckCategory.WORLD,
                "Block placed against no solid face (mid-air placement)");
    }

    @Override
    public void onTick(CheckContext ctx) {
        if (ctx.unstableBasic()) return;
        UUID id = ctx.data.getUuid();
        long last = seen.getOrDefault(id, Long.MIN_VALUE);
        long max = last;

        // Chronological for the break→place race window.
        List<CapturedPacket> recent = ctx.state.log.recent(96);
        List<CapturedPacket> chrono = new ArrayList<>(recent);
        java.util.Collections.reverse(chrono);

        World w = ctx.player.getWorld();
        long breakGraceNs = cfgL("break-grace-ns", 250_000_000L);

        for (CapturedPacket p : chrono) {
            if (p.kind != PacketKind.BLOCK_PLACE || !p.hasPos) continue;
            if (p.seq > max) max = p.seq;
            if (p.seq <= last) continue;

            int bx = (int) p.x, by = (int) p.y, bz = (int) p.z;

            // Was a START_DIGGING for THIS exact block sent in the last ~250ms?
            // (The MC-279849 same-tick break-and-replace race.) If so, the
            // packet's "clicked block" was momentarily solid before the air
            // window we now observe — give it a pass.
            boolean justBroken = false;
            for (CapturedPacket q : chrono) {
                if (q.kind != PacketKind.DIGGING) continue;
                if (!"START_DIGGING".equals(q.strA) && !"FINISHED_DIGGING".equals(q.strA)) continue;
                if (!q.hasPos) continue;
                if ((int) q.x != bx || (int) q.y != by || (int) q.z != bz) continue;
                if (Math.abs(p.recvNanos - q.recvNanos) <= breakGraceNs) { justBroken = true; break; }
            }
            if (justBroken) continue;

            Block clicked = w.getBlockAt(bx, by, bz);
            if (clicked == null) continue;
            // ONLY actual AIR makes a packet "air-place". Cobweb / grass /
            // snow layer / lily pad / fluids (water/lava) are all vanilla-
            // REPLACEABLE blocks — vanilla allows placing against them (the
            // new block replaces them) and the client legitimately sends a
            // PLAYER_BLOCK_PLACEMENT with the replaceable block as the
            // clicked position. Previous version (`!isSolid()`) false-flagged
            // exactly these — placing water with a bucket on a cobweb said
            // "AirPlace". Air is the only case the cheat forges.
            if (!clicked.getType().isAir()) continue;

            // Forged face: the clicked block is actual AIR (nothing to click
            // against). Confirm on streak — a single packet in a chunk-reload
            // race shouldn't flag, but a sustained sequence is conclusive.
            if (diverge(ctx, cfgD("score", 5.0), cfgD("threshold", 9.0),
                    cfgI("min-streak", 2),
                    String.format("place @%d,%d,%d clicked face is AIR (air-place)",
                            bx, by, bz), false)) {
                // No combat-cancel needed; placement is the harm. Rolling back
                // the placed block at the server is the actual prevention, but
                // requires deeper hooks; flagging + punishment pipeline is the
                // first line.
            }
        }
        seen.put(id, max);
    }

    @Override
    public void cleanup(UUID uuid) {
        super.cleanup(uuid);
        seen.remove(uuid);
    }
}
