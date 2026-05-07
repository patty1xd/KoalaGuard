package com.koalaguard.checks;

import com.koalaguard.KoalaGuard;
import org.bukkit.entity.Player;
import org.bukkit.event.Listener;

public abstract class Check implements Listener {

    protected final KoalaGuard plugin;
    protected final String checkName;

    public Check(KoalaGuard plugin, String checkName) {
        this.plugin = plugin;
        this.checkName = checkName;
    }

    public boolean isEnabled() {
        return plugin.getConfig().getBoolean("checks." + checkName.toLowerCase() + ".enabled", true);
    }

    protected void flag(Player player, String detail) {
        if (!isEnabled()) return;
        plugin.getViolationManager().flag(player, checkName, detail);
    }

    protected void flag(Player player) {
        flag(player, "");
    }

    public String getCheckName() {
        return checkName;
    }
}
