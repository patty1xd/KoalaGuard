package com.koalaguard.checks;

import com.koalaguard.KoalaGuard;
import org.bukkit.GameMode;
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

    /**
     * Returns true if this player should be exempt from all checks.
     * Call this at the top of every event handler.
     */
    protected boolean isExempt(Player player) {
        if (player.hasPermission("koalaguard.bypass")) return true;
        GameMode gm = player.getGameMode();
        if (gm == GameMode.CREATIVE || gm == GameMode.SPECTATOR) return true;
        return false;
    }

    protected void flag(Player player, String detail) {
        if (!isEnabled()) return;
        if (isExempt(player)) return;
        plugin.getViolationManager().flag(player, checkName, detail);
    }

    protected void flag(Player player) {
        flag(player, "");
    }

    public String getCheckName() {
        return checkName;
    }
}
