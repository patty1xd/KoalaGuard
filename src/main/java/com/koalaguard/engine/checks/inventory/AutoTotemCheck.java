package com.koalaguard.engine.checks.inventory;

import com.koalaguard.KoalaGuard;
import com.koalaguard.check.CheckCategory;
import com.koalaguard.engine.check.CheckContext;
import com.koalaguard.engine.check.SimCheck;
import com.koalaguard.engine.packet.PacketKind;
import com.koalaguard.engine.state.InventoryState;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * AutoTotem — modelled EXACTLY as the spec describes: not "pop, wait, measure
 * re-equip time", but a valid-transition-path machine.
 *
 * Valid sequence:
 *   1. a totem is consumed out of a hand (resurrection / mirror transition),
 *   2. an interaction chain is observed in the packet stream — either a
 *      SWAP_ITEM_WITH_OFFHAND digging packet or a CLICK_WINDOW (the human
 *      moving a totem back),
 *   3. an intermediate empty/other state may exist briefly,
 *   4. a totem re-appears in a hand.
 *
 * Invalid sequence (flagged):
 *   • survival happened, a totem re-appears in the off/main hand, but NO
 *     SWAP/CLICK interaction exists in the log between the consume tick and
 *     the re-equip tick — i.e. the offhand became a totem with no valid
 *     transition path.
 *
 * The legit "carrying a stack / two totems" case never arms this: the engine
 * only sets {@code awaitingTotemTransition} on a true type transition out of a
 * hand, not on a stack decrement, so a held stack produces no cycle at all.
 * There is NO timing threshold anywhere in this check.
 */
public final class AutoTotemCheck extends SimCheck {

    private final Map<UUID, Boolean> armed = new ConcurrentHashMap<>();

    public AutoTotemCheck(KoalaGuard plugin) {
        super(plugin, "autototem", CheckCategory.COMBAT, "Totem re-equip without a valid transition path");
    }

    @Override
    public void onTick(CheckContext ctx) {
        InventoryState inv = ctx.state.inv;
        UUID id = ctx.data.getUuid();

        if (ctx.data.flagBadBrand) {
            diverge(ctx, 12.0, cfgD("threshold", 8.0), 1,
                    "client brand advertises autototem: " + ctx.data.packetBrand, false);
        }

        // Arm exactly when a totem was consumed out of a hand.
        if (inv.awaitingTotemTransition && Boolean.TRUE != armed.put(id, Boolean.TRUE)) {
            return; // first observation of the armed cycle
        }
        if (!Boolean.TRUE.equals(armed.get(id))) {
            clean(ctx, 0.05);
            return;
        }

        // Re-equip edge: a totem is back in a hand after the consume.
        if (inv.hasTotem()) {
            long consume = inv.totemConsumedTick;

            boolean swap = ctx.state.log.existsSinceTick(consume, p ->
                    p.kind == PacketKind.DIGGING
                    && "SWAP_ITEM_WITH_OFFHAND".equals(p.strA));
            boolean click = ctx.state.log.existsSinceTick(consume, p ->
                    p.kind == PacketKind.CLICK_WINDOW);
            boolean validPath = swap || click;

            armed.put(id, Boolean.FALSE);
            inv.awaitingTotemTransition = false;

            if (!validPath) {
                long span = ctx.state.tick - consume;
                diverge(ctx, cfgD("score", 6.0), cfgD("threshold", 8.0),
                        cfgI("min-streak", 2),
                        "totem re-equipped " + span
                                + " ticks after consume with NO swap/click in the packet chain",
                        false);
            } else {
                clean(ctx, 3.0);
            }
        }
    }

    @Override
    public void cleanup(UUID uuid) {
        super.cleanup(uuid);
        armed.remove(uuid);
    }
}
