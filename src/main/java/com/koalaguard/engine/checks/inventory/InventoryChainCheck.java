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
 * Off-hand interaction-chain validator (generalised AutoTotem).
 *
 * In vanilla, an item can only ENTER the off hand through an observable client
 * packet: a SWAP_ITEM_WITH_OFFHAND (F key) or a CLICK_WINDOW (inventory move).
 * Pick-ups never auto-fill the off hand. So: an item APPEARING in the off hand
 * with no swap/click in the reconstructed stream is an illegal sequence.
 *
 * Deliberately scoped to be false-positive proof:
 *  • only APPEARANCE (→ real item) is judged — item DISappearance (consume /
 *    break / clear) is a legitimate server-side mutation and is never flagged;
 *  • the totem is left to the independent AutoTotem* checks (modular);
 *  • the very first observed inventory state is a baseline, never judged;
 *  • main-hand transitions are intentionally NOT validated here — they have
 *    too many legitimate server-side causes (buckets, crossbows, food, tool
 *    breakage, pickup-to-empty-hand) to validate from packets without FPs.
 */
public final class InventoryChainCheck extends SimCheck {

    private static final class M { long offSeen = Long.MIN_VALUE; }

    private final Map<UUID, M> mem = new ConcurrentHashMap<>();

    public InventoryChainCheck(KoalaGuard plugin) {
        super(plugin, "inventorychain", CheckCategory.COMBAT,
                "Off-hand item appeared with no interaction chain");
    }

    @Override
    public void onTick(CheckContext ctx) {
        if (ctx.unstableBasic()) return;
        InventoryState inv = ctx.state.inv;
        UUID id = ctx.data.getUuid();
        M m = mem.computeIfAbsent(id, k -> new M());

        if (inv.offHandChangedTick <= m.offSeen) return;     // nothing new

        // Baseline: never judge the first inventory snapshot we ever see.
        if (m.offSeen == Long.MIN_VALUE) {
            m.offSeen = inv.offHandChangedTick;
            return;
        }
        long from = m.offSeen;
        m.offSeen = inv.offHandChangedTick;

        Material now = inv.offHand;
        // DISappearance / clear → legitimate server mutation, never flagged.
        if (now == Material.AIR) { clean(ctx, 1.0); return; }
        // Totem cycle is owned by the independent AutoTotem* checks.
        if (now == Material.TOTEM_OF_UNDYING || inv.awaitingTotemTransition) return;

        boolean legal = ctx.state.log.existsSinceTick(from, p ->
                p.kind == PacketKind.CLICK_WINDOW
                || (p.kind == PacketKind.DIGGING
                    && "SWAP_ITEM_WITH_OFFHAND".equals(p.strA)));

        if (!legal) {
            diverge(ctx, cfgD("score", 4.0), cfgD("threshold", 12.0),
                    cfgI("min-streak", 4),
                    "offhand → " + now + " appeared with no swap/click chain", false);
            return;
        }

        // Materialization guard: even with a valid chain, the offhand item
        // must exist SOMEWHERE in the player's inventory at the time. If it
        // doesn't, the item was injected by a creative-give / dispenser / etc.
        // — flag once (much higher streak, very low score) so legit
        // server-side give operations within a tick still don't fire.
        try {
            boolean foundElsewhere = false;
            org.bukkit.inventory.PlayerInventory pi = ctx.player.getInventory();
            for (org.bukkit.inventory.ItemStack it : pi.getContents()) {
                if (it != null && it.getType() == now) { foundElsewhere = true; break; }
            }
            if (!foundElsewhere
                    && pi.getItemInMainHand().getType() != now) {
                diverge(ctx, cfgD("materialize-score", 2.0),
                        cfgD("threshold", 12.0),
                        cfgI("materialize-min-streak", 6),
                        "offhand → " + now + " not present elsewhere in inventory (materialized)",
                        false);
            }
        } catch (Throwable ignored) { }

        clean(ctx, 1.5);
    }

    @Override
    public void cleanup(UUID uuid) {
        super.cleanup(uuid);
        mem.remove(uuid);
    }
}
