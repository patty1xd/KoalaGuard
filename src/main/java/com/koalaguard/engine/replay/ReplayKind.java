package com.koalaguard.engine.replay;

/**
 * Compact union tag for one entry in a rolling replay buffer. Each kind
 * defines which of {@link ReplayFrame}'s primitive fields are meaningful so
 * the serialiser can write a minimal payload per record.
 */
public enum ReplayKind {
    SPAWN,          // x,y,z,yaw,pitch    — initial pose stamped at join
    MOVE,           // x,y,z,yaw,pitch,onGround
    POS,            // x,y,z,onGround
    ROTATE,         // yaw,pitch,onGround
    ATTACK,         // intA = victim entityId; yaw/pitch carry attacker look
    ANIMATE,        // arm swing — yaw/pitch carry look at swing
    SNEAK_START, SNEAK_STOP,
    SPRINT_START, SPRINT_STOP,
    HELD_ITEM,      // intA = slot 0-8
    BLOCK_PLACE,    // (int)x,(int)y,(int)z,byteA=face
    DIG,            // (int)x,(int)y,(int)z,byteA=action ordinal
    USE_ITEM,
    INV_CLICK,      // intA=slot, intB=windowId
    INV_CLOSE,
    HURT,           // damage taken — yaw carries amount (HP)
    DEATH,
    HEALTH;         // yaw=health, pitch=food (compact reuse)

    private static final ReplayKind[] VALS = values();
    public static ReplayKind of(int ordinal) {
        if (ordinal < 0 || ordinal >= VALS.length) return null;
        return VALS[ordinal];
    }
}
