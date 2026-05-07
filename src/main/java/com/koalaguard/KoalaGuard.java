package com.koalaguard;

import com.koalaguard.checks.combat.*;
import com.koalaguard.checks.movement.*;
import com.koalaguard.checks.player.*;
import com.koalaguard.checks.world.*;
import com.koalaguard.commands.KoalaGuardCommand;
import com.koalaguard.listeners.PlayerStateListener;
import com.koalaguard.logging.KoalaGuardLogs;
import com.koalaguard.managers.BanManager;
import com.koalaguard.managers.ViolationManager;
import org.bukkit.plugin.java.JavaPlugin;
import com.koalaguard.util.PlayerStateTracker;
import com.koalaguard.util.ServerMetrics;
import org.bukkit.entity.Player;

public class KoalaGuard extends JavaPlugin {

    private static KoalaGuard instance;
    private ViolationManager violationManager;
    private BanManager banManager;
    private ServerMetrics metrics;
    private PlayerStateTracker playerState;
    private KoalaGuardLogs logs;

    @Override
    public void onEnable() {
        instance = this;
        saveDefaultConfig();

        metrics = new ServerMetrics();
        playerState = new PlayerStateTracker();
        logs = new KoalaGuardLogs(this);

        banManager = new BanManager(this);
        violationManager = new ViolationManager(this);

        registerChecks();
        registerCommands();
        getServer().getPluginManager().registerEvents(
                new com.koalaguard.listeners.PlayerConnectionListener(this), this);
        getServer().getPluginManager().registerEvents(new PlayerStateListener(this), this);

        getLogger().info("KoalaGuard enabled. Protecting the server.");
    }

    @Override
    public void onDisable() {
        if (violationManager != null) violationManager.shutdown();
        getLogger().info("KoalaGuard disabled.");
    }

    private void registerChecks() {
        // Movement
        registerCheck(new SpeedCheck(this));
        registerCheck(new FlyCheck(this));
        registerCheck(new JesusCheck(this));
        registerCheck(new VelocityCheck(this));
        registerCheck(new PhaseCheck(this));
        registerCheck(new BurrowCheck(this));

        // Combat
        registerCheck(new KillAuraCheck(this));
        registerCheck(new AutoClickerCheck(this));
        registerCheck(new ReachCheck(this));
        registerCheck(new CriticalsCheck(this));
        registerCheck(new AimAssistCheck(this));
        registerCheck(new AutoTotemCheck(this));
        registerCheck(new AutoArmorCheck(this));
        registerCheck(new AutoWeaponCheck(this));
        registerCheck(new OffhandCheck(this));
        registerCheck(new BowAimbotCheck(this));
        registerCheck(new SurroundCheck(this));
        registerCheck(new SelfTrapCheck(this));

        // Player
        registerCheck(new AutoEatCheck(this));
        registerCheck(new AutoReplenishCheck(this));
        registerCheck(new AntiHungerCheck(this));
        registerCheck(new RegenCheck(this));
        registerCheck(new AutoFishCheck(this));
        registerCheck(new ExpThrowerCheck(this));
        registerCheck(new ChestSwapCheck(this));
        registerCheck(new SpeedMineCheck(this));

        // World
        registerCheck(new NukerCheck(this));
        registerCheck(new FastBreakCheck(this));
        registerCheck(new AutoSmelterCheck(this));
    }

    private void registerCheck(org.bukkit.event.Listener check) {
        getServer().getPluginManager().registerEvents(check, this);
    }

    private void registerCommands() {
        KoalaGuardCommand cmd = new KoalaGuardCommand(this);
        getCommand("koalaguard").setExecutor(cmd);
        getCommand("koalaguard").setTabCompleter(cmd);
    }

    public static KoalaGuard getInstance() { return instance; }
    public ViolationManager getViolationManager() { return violationManager; }
    public BanManager getBanManager() { return banManager; }
    public ServerMetrics getMetrics() { return metrics; }
    public PlayerStateTracker getPlayerState() { return playerState; }
    public KoalaGuardLogs getLogs() { return logs; }

    public String getPrefix() {
        return getConfig().getString("messages.prefix", "§c[KoalaGuard] §b");
    }

    public String getAlertPermission() {
        return getConfig().getString("alerts.permission", "koalaguard.alerts");
    }

    /**
     * Global anti-false-positive gate. Checks should not flag while:
     * - TPS is low
     * - ping is high
     * - player recently teleported / got velocity / took damage / just joined
     */
    public boolean shouldSuppressFlags(Player player) {
        if (player == null) return true;

        double tps = metrics.tps1m();
        double minTps = getConfig().getDouble("safety.min-tps", 18.0);
        if (tps < minTps) return true;

        int ping = metrics.pingMs(player);
        int maxPing = getConfig().getInt("safety.max-ping-ms", 250);
        if (ping >= 0 && ping >= maxPing) return true;

        long tpWindow = getConfig().getLong("safety.teleport-grace-ms", 1500L);
        long velWindow = getConfig().getLong("safety.velocity-grace-ms", 800L);
        long dmgWindow = getConfig().getLong("safety.damage-grace-ms", 800L);
        long joinWindow = getConfig().getLong("safety.join-grace-ms", 2000L);

        if (playerState.recentlyTeleported(player, tpWindow)) return true;
        if (playerState.recentlyHadVelocity(player, velWindow)) return true;
        if (playerState.recentlyTookDamage(player, dmgWindow)) return true;
        return playerState.recentlyJoined(player, joinWindow);
    }
}
