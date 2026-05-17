package com.koalaguard.engine.state;

/**
 * One reconstructed "totem cycle" — the TotemGuard model.
 *
 * A cycle is: the player pops a totem (lethal damage absorbed) → the client
 * sends the inventory packets that move a fresh totem back into the off hand →
 * the off hand holds a totem again. Every field here is a RECONSTRUCTED FACT
 * timed from netty-thread packet nanoseconds (not the 50 ms server tick, not a
 * Bukkit event that synthetic packet moves skip). The independent AutoTotem*
 * checks read these facts read-only and each apply ONE statistic.
 */
public final class TotemCycle {

    /** Monotonic id so each check processes a cycle exactly once. */
    public final long seq;

    /** Pop → off-hand-totem-again, milliseconds (packet-precise). */
    public final double cycleMs;

    /** First → last re-equip packet in the burst, milliseconds (Type A). */
    public final double selectToEquipMs;

    /** Inter-packet gaps (ms) of the re-equip burst, oldest→newest (Type D). */
    public final double[] gapsMs;

    /** Number of inventory-move packets in the burst. */
    public final int burstSize;

    /** A world interaction happened between pop and re-equip — the inventory
     *  SCREEN cannot have been genuinely open (Type F). */
    public final boolean interactedWhileOpen;

    public final long popNanos;
    public final long reequipNanos;

    public TotemCycle(long seq, double cycleMs, double selectToEquipMs,
                      double[] gapsMs, int burstSize, boolean interactedWhileOpen,
                      long popNanos, long reequipNanos) {
        this.seq = seq;
        this.cycleMs = cycleMs;
        this.selectToEquipMs = selectToEquipMs;
        this.gapsMs = gapsMs;
        this.burstSize = burstSize;
        this.interactedWhileOpen = interactedWhileOpen;
        this.popNanos = popNanos;
        this.reequipNanos = reequipNanos;
    }
}
