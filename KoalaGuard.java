package com.koalaguard;

import com.koalaguard.checks.combat.*;
import com.koalaguard.checks.movement.*;
import com.koalaguard.checks.player.*;
import com.koalaguard.checks.world.*;
import com.koalaguard.commands.KoalaGuardCommand;
import com.koalaguard.managers.BanManager;
import com.koalaguard.managers.ViolationManager;
import org.bukkit.plugin.java.JavaPlugin;

public class KoalaGuard extends JavaPlugin {

    private static KoalaGuard instance;
    private ViolationManager violationManager;
    private BanManager banManager;

    @Override
    public void onEnable() {
        instance = this;
        saveDefaultConfig();

        banManager = new BanManager(this);
        violationManager = new ViolationManager(this);

        registerChecks();
        registerCommands();
        getServer().getPluginManager().registerEvents(
                new com.koalaguard.listeners.PlayerConnectionListener(this), this);

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
        registerCheck(new NoFallCheck(this));
        registerCheck(new StepCheck(this));
        registerCheck(new JesusCheck(this));
        registerCheck(new VelocityCheck(this));
        registerCheck(new SprintCheck(this));
        registerCheck(new ScaffoldCheck(this));
        registerCheck(new HighJumpCheck(this));
        registerCheck(new LongJumpCheck(this));
        registerCheck(new ElytraBoostCheck(this));
        registerCheck(new TridentBoostCheck(this));
        registerCheck(new PhaseCheck(this));
        registerCheck(new BurrowCheck(this));
        registerCheck(new FastClimbCheck(this));

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
        registerCheck(new VeinMinerCheck(this));
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
}
