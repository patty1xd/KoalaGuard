package com.koalaguard.check;

import com.koalaguard.KoalaGuard;
import com.koalaguard.data.PlayerData;
import org.bukkit.entity.Player;

/**
 * Driven once per movement tick by the MovementProcessor. The shared
 * movement model in {@link PlayerData} is already populated when
 * {@link #handle} is called.
 */
public abstract class MovementCheck extends Check {

    protected MovementCheck(KoalaGuard plugin, String name, String description) {
        super(plugin, name, CheckCategory.MOVEMENT, description);
    }

    public abstract void handle(PlayerData data, Player player);
}
