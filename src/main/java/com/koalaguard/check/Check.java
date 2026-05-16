package com.koalaguard.check;

import com.koalaguard.KoalaGuard;
import com.koalaguard.data.PlayerData;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;

/**
 * Base of every detection. Checks no longer keep their own player maps —
 * all per-player state lives in {@link PlayerData}. A check increases a
 * decimal "buffer" on suspicion and decays it on clean behaviour; only a
 * sustained buffer flags, which is what keeps false positives near zero.
 */
public abstract class Check {

    protected final KoalaGuard plugin;
    private final String name;
    private final CheckCategory category;
    private final String description;

    protected Check(KoalaGuard plugin, String name, CheckCategory category, String description) {
        this.plugin = plugin;
        this.name = name;
        this.category = category;
        this.description = description;
    }

    public String getName() { return name; }
    public CheckCategory getCategory() { return category; }
    public String getDescription() { return description; }

    // ───────────────────────── config helpers ─────────────────────────
    public boolean isEnabled() {
        return plugin.getConfig().getBoolean("checks." + name + ".enabled", true);
    }
    public int maxViolations() {
        return plugin.getConfig().getInt("checks." + name + ".max-violations", 15);
    }
    public double vlPerFlag() {
        return plugin.getConfig().getDouble("checks." + name + ".vl-per-flag", 1.0);
    }
    public String punishment() {
        return plugin.getConfig().getString("checks." + name + ".punishment", "kick");
    }
    public String banDuration() {
        return plugin.getConfig().getString("checks." + name + ".ban-duration", "7d");
    }
    protected double cfgD(String key, double def) {
        return plugin.getConfig().getDouble("checks." + name + "." + key, def);
    }
    protected int cfgI(String key, int def) {
        return plugin.getConfig().getInt("checks." + name + "." + key, def);
    }
    protected long cfgL(String key, long def) {
        return plugin.getConfig().getLong("checks." + name + "." + key, def);
    }
    protected boolean cfgB(String key, boolean def) {
        return plugin.getConfig().getBoolean("checks." + name + "." + key, def);
    }

    /** Namespaces a scratch key so two checks never collide in PlayerData. */
    protected String k(String sub) { return name + "$" + sub; }

    // ───────────────────────── exemptions ─────────────────────────────
    protected boolean isExempt(Player player) {
        if (player == null || !player.isOnline()) return true;
        if (player.hasPermission("koalaguard.bypass")) return true;
        GameMode gm = player.getGameMode();
        return gm == GameMode.CREATIVE || gm == GameMode.SPECTATOR;
    }

    // ───────────────────────── flag plumbing ──────────────────────────

    /** Raise the check buffer; flags only once it crosses the per-flag step. */
    protected void fail(PlayerData data, Player player, String detail) {
        if (data == null || !isEnabled() || isExempt(player)) return;
        if (plugin.getSafetyManager().shouldSuppress(data, player)) return;
        plugin.getViolationManager().fail(this, data, player, detail == null ? "" : detail);
    }

    protected void fail(PlayerData data, Player player) {
        fail(data, player, "");
    }

    /** Clean behaviour — gently bleed off this check's violation level. */
    protected void reward(PlayerData data) {
        if (data == null) return;
        plugin.getViolationManager().reward(this, data);
    }

    /** Flag AND lagback the player to their last prediction-valid position. */
    protected void failAndSetback(PlayerData data, Player player, String detail) {
        fail(data, player, detail);
        plugin.getSetbackManager().requestSetback(data, player, getName() + ": " + detail);
    }

    /**
     * Flag WITHOUT re-applying the movement-grace suppression. Engine checks
     * already make their own (correct, per-category) instability decision via
     * the CheckContext before they ever call this, so re-running the broad
     * {@code shouldSuppress} here would, for combat checks, silently drop every
     * flag during a fight (the attacker just took damage). Exemption + enabled
     * are still enforced.
     */
    protected void failResolved(PlayerData data, Player player, String detail) {
        if (data == null || !isEnabled() || isExempt(player)) return;
        plugin.getViolationManager().fail(this, data, player, detail == null ? "" : detail);
    }

    protected void failResolvedAndSetback(PlayerData data, Player player, String detail) {
        failResolved(data, player, detail);
        plugin.getSetbackManager().requestSetback(data, player, getName() + ": " + detail);
    }

    protected boolean debug() {
        return plugin.getConfig().getBoolean("debug", false);
    }
}
