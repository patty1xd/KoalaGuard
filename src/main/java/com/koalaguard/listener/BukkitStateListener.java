package com.koalaguard.listener;

import com.koalaguard.KoalaGuard;
import com.koalaguard.data.PlayerData;
import com.koalaguard.engine.state.CombatState;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityResurrectEvent;
import org.bukkit.event.player.*;

/**
 * The ONLY Bukkit-event surface left. It does no detection — it just owns the
 * {@link PlayerData} lifecycle, enforces bans at login, and stamps the
 * grace/knockback facts that are simply not available on the packet wire
 * (server-applied velocity, world change, resurrection). All judgement happens
 * later in the engine against reconstructed state.
 */
public final class BukkitStateListener implements Listener {

    private final KoalaGuard plugin;

    public BukkitStateListener(KoalaGuard plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onLogin(PlayerLoginEvent event) {
        Player p = event.getPlayer();
        if (plugin.getBanManager().isBanned(p.getUniqueId())) {
            String reason = plugin.getBanManager().getBanReason(p.getUniqueId());
            String expiry = plugin.getBanManager().getBanExpiry(p.getUniqueId());
            event.disallow(PlayerLoginEvent.Result.KICK_BANNED,
                    plugin.getBanManager().banScreen(reason, expiry));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        plugin.getDataManager().create(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        plugin.getDataManager().remove(event.getPlayer().getUniqueId());
        plugin.getViolationManager().clearPlayer(event.getPlayer().getUniqueId());
        plugin.getEngine().cleanup(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        PlayerData d = plugin.getDataManager().get(event.getPlayer());
        if (d == null) return;
        d.lastTeleportMs = System.currentTimeMillis();
        d.engine.moveInit = false;             // re-baseline reconstruction
        if (event.getFrom().getWorld() != null && event.getTo() != null
                && !event.getFrom().getWorld().equals(event.getTo().getWorld())) {
            d.lastWorldChangeMs = System.currentTimeMillis();
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldChange(PlayerChangedWorldEvent event) {
        PlayerData d = plugin.getDataManager().get(event.getPlayer());
        if (d != null) {
            d.lastWorldChangeMs = System.currentTimeMillis();
            d.engine.moveInit = false;
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onVelocity(PlayerVelocityEvent event) {
        PlayerData d = plugin.getDataManager().get(event.getPlayer());
        if (d == null) return;
        d.lastVelocityMs = System.currentTimeMillis();
        d.pendingVelocity = event.getVelocity().clone();
        d.pendingVelocityMs = d.lastVelocityMs;
        CombatState c = d.engine.combat;
        c.pendingKnockback = event.getVelocity().clone();
        c.knockbackTick = d.engine.tick;
        c.knockbackConsumed = false;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player p)) return;
        PlayerData d = plugin.getDataManager().get(p);
        if (d != null) {
            d.lastDamageMs = System.currentTimeMillis();
            d.engine.combat.lastDamageTakenTick = d.engine.tick;
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onResurrect(EntityResurrectEvent event) {
        if (!(event.getEntity() instanceof Player p)) return;
        PlayerData d = plugin.getDataManager().get(p);
        if (d == null) return;
        // EXACT pop moment — fires before any refill, so an instant autototem
        // cannot hide it. seq is a pure counter (advances even while perfectly
        // still); popConf/nanos give a movement-independent "since pop" bound.
        // NOTE: no stack guard. A legit player carrying a totem STACK produces
        // ZERO click packets (the stack just decrements), so the packet-
        // behaviour signals below never fire for them — that, not skipping the
        // cycle, is what makes the two-totem case false-positive proof. The old
        // amount>=2 skip is exactly why stack-based autototems were invisible.
        var inv = d.engine.inv;
        inv.totemConsumedTick = d.engine.tick;
        inv.totemPopConf = d.confirmedTransactions;
        inv.totemPopNanos = System.nanoTime();
        inv.totemPopSeq++;
        inv.awaitingTotemTransition = true;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerRespawnEvent event) {
        PlayerData d = plugin.getDataManager().get(event.getPlayer());
        if (d != null) { d.lastRespawnMs = System.currentTimeMillis(); d.engine.moveInit = false; }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onGameMode(PlayerGameModeChangeEvent event) {
        PlayerData d = plugin.getDataManager().get(event.getPlayer());
        if (d != null) d.gamemodeChangeMs = System.currentTimeMillis();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMoveState(PlayerMoveEvent event) {
        if (event.getTo() == null) return;
        Player p = event.getPlayer();
        PlayerData d = plugin.getDataManager().get(p);
        if (d == null) return;
        long now = System.currentTimeMillis();
        if (p.isRiptiding()) d.lastRiptideMs = now;
        if (p.isGliding()) d.elytraMs = now;
        Material at = event.getTo().getBlock().getType();
        if (at == Material.BUBBLE_COLUMN) d.bubbleColumnMs = now;
        if (event.getTo().getY() > event.getFrom().getY()) {
            Material below = event.getTo().clone().subtract(0, 1, 0).getBlock().getType();
            if (below == Material.SLIME_BLOCK) d.slimeBounceMs = now;
            if (below == Material.SLIME_BLOCK || below.name().endsWith("_BED")) d.lastSlimeOrBedMs = now;
        }
    }
}
