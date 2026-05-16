package com.koalaguard;

import com.koalaguard.alert.AlertManager;
import com.koalaguard.check.CheckManager;
import com.koalaguard.command.KoalaGuardCommand;
import com.koalaguard.data.DataManager;
import com.koalaguard.discord.DiscordBot;
import com.koalaguard.listener.StateListener;
import com.koalaguard.logging.KoalaGuardLogs;
import com.koalaguard.manager.BanManager;
import com.koalaguard.manager.SafetyManager;
import com.koalaguard.manager.ViolationManager;
import com.koalaguard.processor.CombatProcessor;
import com.koalaguard.processor.MovementProcessor;
import com.koalaguard.util.ServerMetrics;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

public final class KoalaGuard extends JavaPlugin {

    private static KoalaGuard instance;

    private ServerMetrics metrics;
    private DataManager dataManager;
    private SafetyManager safetyManager;
    private BanManager banManager;
    private KoalaGuardLogs logs;
    private DiscordBot discordBot;
    private AlertManager alertManager;
    private ViolationManager violationManager;
    private CheckManager checkManager;

    @Override
    public void onEnable() {
        instance = this;
        saveDefaultConfig();

        metrics       = new ServerMetrics();
        dataManager   = new DataManager();
        safetyManager = new SafetyManager(this);
        banManager    = new BanManager(this);
        logs          = new KoalaGuardLogs(this);
        discordBot    = new DiscordBot(this);
        discordBot.start();
        alertManager     = new AlertManager(this);
        violationManager = new ViolationManager(this);

        checkManager = new CheckManager(this);
        checkManager.registerAll();

        Bukkit.getPluginManager().registerEvents(new MovementProcessor(this), this);
        Bukkit.getPluginManager().registerEvents(new CombatProcessor(this), this);
        Bukkit.getPluginManager().registerEvents(new StateListener(this), this);

        KoalaGuardCommand cmd = new KoalaGuardCommand(this);
        getCommand("koalaguard").setExecutor(cmd);
        getCommand("koalaguard").setTabCompleter(cmd);

        // Cover players already online (e.g. /reload)
        Bukkit.getOnlinePlayers().forEach(p -> dataManager.create(p));

        getLogger().info("KoalaGuard v" + getPluginMeta().getVersion() + " enabled — protecting "
                + Bukkit.getOnlinePlayers().size() + " player(s).");
    }

    @Override
    public void onDisable() {
        if (violationManager != null) violationManager.shutdown();
        if (discordBot != null) discordBot.shutdown();
        if (dataManager != null) dataManager.clear();
        getLogger().info("KoalaGuard disabled.");
    }

    public static KoalaGuard getInstance()      { return instance; }
    public ServerMetrics getMetrics()           { return metrics; }
    public DataManager getDataManager()         { return dataManager; }
    public SafetyManager getSafetyManager()     { return safetyManager; }
    public BanManager getBanManager()           { return banManager; }
    public KoalaGuardLogs getLogs()             { return logs; }
    public DiscordBot getDiscordBot()           { return discordBot; }
    public AlertManager getAlertManager()       { return alertManager; }
    public ViolationManager getViolationManager() { return violationManager; }
    public CheckManager getCheckManager()       { return checkManager; }
}
