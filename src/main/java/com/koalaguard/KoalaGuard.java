package com.koalaguard;

import com.github.retrooper.packetevents.PacketEvents;
import com.koalaguard.alert.AlertManager;
import com.koalaguard.command.KoalaGuardCommand;
import com.koalaguard.data.DataManager;
import com.koalaguard.discord.DiscordBot;
import com.koalaguard.engine.EngineManager;
import com.koalaguard.engine.EngineTask;
import com.koalaguard.engine.lag.TransactionDriver;
import com.koalaguard.engine.netty.RawNettyInjector;
import com.koalaguard.engine.packet.PacketCaptureListener;
import com.koalaguard.engine.replay.ReplayManager;
import com.koalaguard.listener.BukkitStateListener;
import com.koalaguard.logging.KoalaGuardLogs;
import com.koalaguard.manager.BanManager;
import com.koalaguard.manager.SafetyManager;
import com.koalaguard.manager.ViolationManager;
import com.koalaguard.processor.SetbackManager;
import com.koalaguard.util.ServerMetrics;
import io.github.retrooper.packetevents.factory.spigot.SpigotPacketEventsBuilder;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * KoalaGuard — server-authoritative anticheat.
 *
 * The whole plugin is one pipeline:
 *   netty {@link PacketCaptureListener}  →  per-tick {@link EngineTask}
 *   (state machine + simulator)          →  modular checks (violation scoring)
 *   →  kept alert / Discord / punishment infrastructure.
 *
 * There is no event-driven or timing-threshold detection anywhere.
 */
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
    private SetbackManager setbackManager;
    private ReplayManager replayManager;
    private EngineManager engine;
    private RawNettyInjector nettyInjector;

    @Override
    public void onLoad() {
        PacketEvents.setAPI(SpigotPacketEventsBuilder.build(this));
        PacketEvents.getAPI().getSettings()
                .checkForUpdates(false)
                .reEncodeByDefault(false);
        PacketEvents.getAPI().load();
    }

    @Override
    public void onEnable() {
        instance = this;
        saveDefaultConfig();

        metrics          = new ServerMetrics();
        dataManager      = new DataManager();
        safetyManager    = new SafetyManager(this);
        banManager       = new BanManager(this);
        logs             = new KoalaGuardLogs(this);
        discordBot       = new DiscordBot(this);
        discordBot.start();
        alertManager     = new AlertManager(this);
        violationManager = new ViolationManager(this);
        setbackManager   = new SetbackManager(this);
        replayManager    = new ReplayManager(this);

        engine = new EngineManager(this);
        engine.registerAll();

        // Unified packet capture (netty thread) — pure ground-truth recording.
        PacketEvents.getAPI().getEventManager().registerListener(new PacketCaptureListener(this));
        PacketEvents.getAPI().init();

        // Raw netty channel-handler injection sits BELOW PacketEvents for
        // byte-level frame metrics (length distribution, packet-id histogram,
        // outbound byte rate). Read-only; PacketEvents and Mojang both see
        // every frame unchanged.
        nettyInjector = new RawNettyInjector(this);

        // Lag-comp transaction clock, then the per-tick engine driver.
        new TransactionDriver(this).runTaskTimer(this, 1L, 1L);
        new EngineTask(this).runTaskTimer(this, 1L, 1L);

        Bukkit.getPluginManager().registerEvents(new BukkitStateListener(this), this);

        KoalaGuardCommand cmd = new KoalaGuardCommand(this);
        getCommand("koalaguard").setExecutor(cmd);
        getCommand("koalaguard").setTabCompleter(cmd);

        Bukkit.getOnlinePlayers().forEach(p -> {
            dataManager.create(p);
            replayManager.start(p);
            nettyInjector.inject(p);
        });

        getLogger().info("KoalaGuard v" + getPluginMeta().getVersion()
                + " enabled (server-authoritative engine) — protecting "
                + Bukkit.getOnlinePlayers().size() + " player(s).");
    }

    @Override
    public void onDisable() {
        if (nettyInjector != null) nettyInjector.uninjectAll();
        if (violationManager != null) violationManager.shutdown();
        if (discordBot != null) discordBot.shutdown();
        if (dataManager != null) dataManager.clear();
        try { PacketEvents.getAPI().terminate(); } catch (Throwable ignored) {}
        getLogger().info("KoalaGuard disabled.");
    }

    public static KoalaGuard getInstance()        { return instance; }
    public ServerMetrics getMetrics()             { return metrics; }
    public DataManager getDataManager()           { return dataManager; }
    public SafetyManager getSafetyManager()       { return safetyManager; }
    public BanManager getBanManager()             { return banManager; }
    public KoalaGuardLogs getLogs()               { return logs; }
    public DiscordBot getDiscordBot()             { return discordBot; }
    public AlertManager getAlertManager()         { return alertManager; }
    public ViolationManager getViolationManager() { return violationManager; }
    public SetbackManager getSetbackManager()     { return setbackManager; }
    public ReplayManager getReplayManager()       { return replayManager; }
    public EngineManager getEngine()              { return engine; }
    public RawNettyInjector getNettyInjector()    { return nettyInjector; }
}
