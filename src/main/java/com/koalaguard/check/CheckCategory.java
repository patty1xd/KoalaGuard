package com.koalaguard.check;

public enum CheckCategory {
    MOVEMENT("Movement"),
    COMBAT("Combat"),
    PLAYER("Player"),
    WORLD("World"),
    PACKET("Packet");

    private final String display;
    CheckCategory(String display) { this.display = display; }
    public String display() { return display; }
}
