package com.koalaguard.engine.packet;

/**
 * Every packet class the engine reconstructs state from. The capture layer is
 * unified: one listener records ALL of these into a single ordered log so any
 * player action can be replayed from the stream alone.
 */
public enum PacketKind {
    MOVEMENT,        // PLAYER_FLYING / POSITION / ROTATION / POSITION_AND_ROTATION
    INTERACT_ENTITY, // attack / interact on an entity
    ANIMATION,       // arm swing
    ENTITY_ACTION,   // sprint / sneak start-stop
    HELD_ITEM,       // hotbar slot change
    DIGGING,         // start/stop/finish dig, release-use, swap-offhand
    USE_ITEM,        // right-click use
    BLOCK_PLACE,     // block placement
    CLICK_WINDOW,    // inventory click
    CLOSE_WINDOW,    // inventory close
    PONG,            // transaction confirmation (tick clock + RTT)
    STEER_VEHICLE,   // boat/horse steering (BoatFly authoritative source)
    PLAYER_INPUT,    // 1.21.2+ keyboard state — input/movement consistency
    TELEPORT_CONFIRM // pearl/tp sequence — burst/double-accept fingerprinting
}
