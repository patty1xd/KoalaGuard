package com.koalaguard;

import com.koalaguard.checks.combat.*;
import com.koalaguard.checks.movement.*;
import com.koalaguard.checks.player.*;
import com.koalaguard.checks.world.*;
import com.koalaguard.commands.KoalaGuardCommand;
import com.koalaguard.discord.DiscordBot;
import com.koalaguard.listeners.PlayerConnectionListener;
import com.koalaguard.listeners.PlayerStateListener;
import com.koalaguard.logging.KoalaGuardLogs;
import com.koalaguard.managers.BanManager;
import com.koalaguard.managers.ViolationManager;
import com.koalaguard.util.PlayerStateTracker;
import com.koalaguard.util.ServerMetrics;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

public class KoalaGuard extends JavaPlugin {

    private static KoalaGuard instance;

    private ViolationManager violationManager;
    private BanManager       banManager;
    private ServerMetrics    metrics;
    private PlayerStateTracker playerState;
    private KoalaGuardLogs   logs;
    private DiscordBot       discordBot;

    @Override
    public void onEnable() {
        instance = this;
        saveDefaultConfig();

        metrics     = new ServerMetrics();
        playerState = new PlayerStateTracker();
        discordBot  = new DiscordBot(this);
        discordBot.start();

        logs = new KoalaGuardLogs(this, discordBot);

        banManager       = new BanManager(this);
        violationManager = new ViolationManager(this);

        registerChecks();
        registerCommands();

        getServer().getPluginManager().registerEvents(new PlayerConnectionListener(this), this);
        getServer().getPluginManager().registerEvents(new PlayerStateListener(this), this);

        getLogger().info("KoalaGuard v" + getDescription().getVersion() + " enabled.");
    }

    @Override
    public void onDisable() {
        if (violationManager != null) violationManager.shutdown();
        if (discordBot != null) discordBot.shutdown();
        getLogger().info("KoalaGuard disabled.");
    }

    private void registerChecks() {
        // Movement
        register(new SpeedCheck(this));
        register(new FlyCheck(this));
        register(new JesusCheck(this));
        register(new VelocityCheck(this));
        register(new PhaseCheck(this));
        register(new BurrowCheck(this));
        register(new TimerCheck(this));
        register(new ScaffoldCheck(this));

        // Combat
        register(new KillAuraCheck(this));
        register(new AutoClickerCheck(this));
        register(new ReachCheck(this));
        register(new CriticalsCheck(this));
        register(new AimAssistCheck(this));
        register(new AutoTotemCheck(this));
        register(new AutoArmorCheck(this));
        register(new AutoWeaponCheck(this));
        register(new OffhandCheck(this));
        register(new BowAimbotCheck(this));
        register(new SurroundCheck(this));
        register(new SelfTrapCheck(this));
        register(new TBotCheck(this));
        register(new HitboxCheck(this));
        register(new WebAuraCheck(this));

        // Player
        register(new AutoEatCheck(this));
        register(new AutoReplenishCheck(this));
        register(new AntiHungerCheck(this));
        register(new RegenCheck(this));
        register(new AutoFishCheck(this));
        register(new ExpThrowerCheck(this));
        register(new ChestSwapCheck(this));
        register(new SpeedMineCheck(this));

        // World
        register(new NukerCheck(this));
        register(new FastBreakCheck(this));
        register(new AutoSmelterCheck(this));
    }

    private void register(org.bukkit.event.Listener check) {
        getServer().getPluginManager().registerEvents(check, this);
    }

    private void registerCommands() {
        KoalaGuardCommand cmd = new KoalaGuardCommand(this);
        getCommand("koalaguard").setExecutor(cmd);
        getCommand("koalaguard").setTabCompleter(cmd);
    }

    /**
     * Global anti-false-positive gate. Checks that respect ping/TPS
     * safety margins call this first. Returns true if the check should
     * NOT flag the player right now.
     */
    public boolean shouldSuppressFlags(Player player) {
        if (player == null) return true;

        double tps    = metrics.tps1m();
        double minTps = getConfig().getDouble("safety.min-tps", 18.0);
        if (tps < minTps) return true;

        int ping    = metrics.pingMs(player);
        int maxPing = getConfig().getInt("safety.max-ping-ms", 300);
        if (ping >= 0 && ping >= maxPing) return true;

        long tpWindow   = getConfig().getLong("safety.teleport-grace-ms", 1500L);
        long velWindow  = getConfig().getLong("safety.velocity-grace-ms", 800L);
        long dmgWindow  = getConfig().getLong("safety.damage-grace-ms", 600L);
        long joinWindow = getConfig().getLong("safety.join-grace-ms", 3000L);

        if (playerState.recentlyTeleported(player, tpWindow)) return true;
        if (playerState.recentlyHadVelocity(player, velWindow)) return true;
        if (playerState.recentlyTookDamage(player, dmgWindow)) return true;
        if (playerState.recentlyJoined(player, joinWindow)) return true;
        if (playerState.recentlyRespawned(player, 2000L)) return true;
        return playerState.recentlyChangedGameMode(player, 1000L);
    }

    // --- Accessors ---

    public static KoalaGuard getInstance()          { return instance; }
    public ViolationManager getViolationManager()   { return violationManager; }
    public BanManager getBanManager()               { return banManager; }
    public ServerMetrics getMetrics()               { return metrics; }
    public PlayerStateTracker getPlayerState()      { return playerState; }
    public KoalaGuardLogs getLogs()                 { return logs; }
    public DiscordBot getDiscordBot()               { return discordBot; }

    public String getPrefix() {
        return getConfig().getString("messages.prefix", "§c[KoalaGuard] §b");
    }

    public String getAlertPermission() {
        return getConfig().getString("alerts.permission", "koalaguard.alerts");
    }
}
