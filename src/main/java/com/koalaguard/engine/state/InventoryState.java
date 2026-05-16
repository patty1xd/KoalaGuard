package com.koalaguard.engine.state;

import org.bukkit.Material;

/**
 * Server-side mirror of the slots the engine reasons about. Refreshed once per
 * tick on the main thread from the authoritative inventory, and annotated with
 * the tick at which each hand last CHANGED. Checks compare these transitions
 * against the packet stream — they never time a "pop → re-equip" stopwatch.
 */
public final class InventoryState {

    public Material mainHand = Material.AIR;
    public Material offHand  = Material.AIR;
    public Material cursor   = Material.AIR;
    public int heldSlot;
    public int offHandCount;     // stack size in the off hand (stack vs single)
    public int mainHandCount;

    public long mainHandChangedTick = -1;
    public long offHandChangedTick  = -1;

    /** Tick at which a totem was last observed consumed out of a hand. */
    public long totemConsumedTick = -1;
    public boolean awaitingTotemTransition;

    // Window lifecycle reconstructed purely from packets.
    public boolean containerOpen;
    public int openWindowId = -1;
    public long windowOpenedTick = -1;
    public long windowClosedTick = -1;

    public boolean hasTotem() {
        return mainHand == Material.TOTEM_OF_UNDYING || offHand == Material.TOTEM_OF_UNDYING;
    }
}
