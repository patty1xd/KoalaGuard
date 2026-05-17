package com.koalaguard.engine.state;

import org.bukkit.Material;

import java.util.ArrayDeque;
import java.util.Deque;

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

    /**
     * Pop bookkeeping driven by EntityResurrectEvent (the exact pop moment,
     * fired before any refill — so an instant autototem can't hide it).
     * {@code totemPopSeq} is a pure counter (NOT a tick clock) so it advances
     * even while the player is perfectly still — which is how autototem is
     * normally tested. {@code totemPopConf} snapshots the always-advancing
     * confirmed-transaction clock for an accurate, movement-independent
     * re-equip interval.
     */
    public volatile long totemPopSeq;
    public volatile long totemPopConf;
    public volatile long totemPopNanos;   // clock-independent "since pop" bound
    public volatile boolean popPending;   // a pop is awaiting its re-equip

    /**
     * Re-equip bookkeeping driven by Bukkit InventoryClickEvent /
     * PlayerSwapHandItemsEvent — the server fires these no matter HOW the
     * cheat moves the totem (raw-packet decoding is unreliable across MC
     * versions). A legit totem STACK carry fires NEITHER event (the stack just
     * decrements) so it can never be flagged.
     */
    public volatile long reequipSeq;
    public volatile long reequipConf;
    public volatile long reequipNanos;
    public volatile boolean reequipBusy;  // re-equip happened while attacking

    // Window lifecycle reconstructed purely from packets.
    public boolean containerOpen;
    public int openWindowId = -1;
    public long windowOpenedTick = -1;
    public long windowClosedTick = -1;

    public boolean hasTotem() {
        return mainHand == Material.TOTEM_OF_UNDYING || offHand == Material.TOTEM_OF_UNDYING;
    }

    // ───────────────── TotemGuard totem-cycle model ─────────────────
    /**
     * Recent COMPLETED totem cycles, reconstructed packet-precisely by
     * {@code TotemCycleProcessor}. The independent AutoTotem* checks read this
     * read-only; none of them mutate it or each other.
     */
    public final Deque<TotemCycle> cycles = new ArrayDeque<>();
    public long cycleSeq;                 // id assigned to each completed cycle

    // Processor scratch (single-threaded, main-thread engine only).
    public boolean cycleActive;           // a pop/vuln is awaiting its re-equip
    public long cyclePopNanos;
    public long cyclePopTick;
    public long lastProcessedPopSeq = Long.MIN_VALUE;

    /**
     * "Vulnerability" trigger: the player took damage while the off hand held
     * NO totem. A legit player would (slowly, manually) equip one or just not;
     * an autototem slams one into the off hand inhumanly fast. This is the
     * FIRST-totem case (no prior pop) — stamped by the damage listener and
     * reconstructed into a normal cycle by the processor.
     */
    public volatile long vulnNanos;
    public volatile long vulnTick;
    public volatile long vulnSeq;
    public long lastProcessedVulnSeq = Long.MIN_VALUE;

    // BadPackets Type C — last inventory-click signature for duplicate detect.
    public int lastClickSlot = Integer.MIN_VALUE;
    public int lastClickWindow = Integer.MIN_VALUE;
    public long lastClickNanos = Long.MIN_VALUE;
}
