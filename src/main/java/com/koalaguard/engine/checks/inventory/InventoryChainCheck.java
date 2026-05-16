package com.koalaguard.engine.checks.inventory;

import com.koalaguard.KoalaGuard;
import com.koalaguard.check.CheckCategory;
import com.koalaguard.engine.check.CheckContext;
import com.koalaguard.engine.check.SimCheck;
import com.koalaguard.engine.packet.PacketKind;
import com.koalaguard.engine.state.InventoryState;
import org.bukkit.Material;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * General interaction-chain validator. A hand's contents may only change as
 * the RESULT of a legal client packet sequence:
 *   • main hand  ⟵ HELD_ITEM (hotbar) or CLICK_WINDOW (item move),
 *   • off  hand  ⟵ DIGGING/SWAP_ITEM_WITH_OFFHAND or CLICK_WINDOW.
 *
 * A hand transition with NO causing packet in the reconstructed stream is an
 * illegal sequence (packet-only inventory manipulation). Server-side mutations
 * (pickups, durability, plugins) are rare and non-persistent, so the high
 * persistence requirement keeps this false-positive free. The totem cycle is
 * left to {@link AutoTotemCheck} (modular separation).
 */
public final class InventoryChainCheck extends SimCheck {

    private static final class M { long mainSeen = -1, offSeen = -1; }

    private final Map<UUID, M> mem = new ConcurrentHashMap<>();

    public InventoryChainCheck(KoalaGuard plugin) {
        super(plugin, "inventorychain", CheckCategory.COMBAT,
                "Illegal hand transition (no interaction chain)");
    }

    @Override
    public void onTick(CheckContext ctx) {
        if (ctx.unstableBasic()) return;
        InventoryState inv = ctx.state.inv;
        UUID id = ctx.data.getUuid();
        M m = mem.computeIfAbsent(id, k -> new M());

        // ── off-hand transition legality ──
        if (inv.offHandChangedTick > m.offSeen) {
            long from = m.offSeen < 0 ? inv.offHandChangedTick - 1 : m.offSeen;
            m.offSeen = inv.offHandChangedTick;
            boolean totemCycle = inv.offHand == Material.TOTEM_OF_UNDYING
                    && inv.awaitingTotemTransition;
            if (!totemCycle) {
                boolean legal = ctx.state.log.existsSinceTick(from, p ->
                        p.kind == PacketKind.CLICK_WINDOW
                        || (p.kind == PacketKind.DIGGING
                            && "SWAP_ITEM_WITH_OFFHAND".equals(p.strA)));
                if (!legal) {
                    diverge(ctx, cfgD("score", 4.0), cfgD("threshold", 12.0),
                            cfgI("min-streak", 4),
                            "offhand → " + inv.offHand + " with no swap/click chain", false);
                } else {
                    clean(ctx, 1.0);
                }
            }
        }

        // ── main-hand transition legality ──
        if (inv.mainHandChangedTick > m.mainSeen) {
            long from = m.mainSeen < 0 ? inv.mainHandChangedTick - 1 : m.mainSeen;
            m.mainSeen = inv.mainHandChangedTick;
            boolean legal = ctx.state.log.existsSinceTick(from, p ->
                    p.kind == PacketKind.HELD_ITEM
                    || p.kind == PacketKind.CLICK_WINDOW
                    || p.kind == PacketKind.CLOSE_WINDOW);
            if (!legal) {
                diverge(ctx, cfgD("score", 4.0), cfgD("threshold", 12.0),
                        cfgI("min-streak", 5),
                        "mainhand → " + inv.mainHand + " with no held/click chain", false);
            } else {
                clean(ctx, 1.0);
            }
        }
    }

    @Override
    public void cleanup(UUID uuid) {
        super.cleanup(uuid);
        mem.remove(uuid);
    }
}
