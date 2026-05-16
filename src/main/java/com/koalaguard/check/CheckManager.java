package com.koalaguard.check;

import com.koalaguard.KoalaGuard;
import com.koalaguard.checks.combat.*;
import com.koalaguard.checks.movement.*;
import com.koalaguard.checks.player.*;
import com.koalaguard.checks.world.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Registers every check and exposes the movement / combat lists that the
 * processors iterate. Listener-style checks register their own handlers.
 */
public final class CheckManager {

    private final KoalaGuard plugin;
    private final List<MovementCheck> movementChecks = new ArrayList<>();
    private final List<CombatCheck> combatChecks = new ArrayList<>();
    private final List<ListenerCheck> listenerChecks = new ArrayList<>();
    private final Map<String, Check> byName = new LinkedHashMap<>();

    public CheckManager(KoalaGuard plugin) {
        this.plugin = plugin;
    }

    public void registerAll() {
        // ── Movement (single PlayerMoveEvent pipeline) ──
        add(new SpeedCheck(plugin));
        add(new StrafeCheck(plugin));
        add(new MotionCheck(plugin));
        add(new FlyCheck(plugin));
        add(new NoFallCheck(plugin));
        add(new StepCheck(plugin));
        add(new JesusCheck(plugin));
        add(new NoSlowCheck(plugin));
        add(new SprintCheck(plugin));
        add(new PhaseCheck(plugin));
        add(new TimerCheck(plugin));
        add(new FastClimbCheck(plugin));
        add(new AntiHungerCheck(plugin));
        add(new WebCheck(plugin));
        add(new ElytraFlyCheck(plugin));
        add(new BlinkCheck(plugin));

        // ── Combat (attack pipeline) ──
        add(new ReachCheck(plugin));
        add(new KillAuraCheck(plugin));
        add(new AutoClickerCheck(plugin));
        add(new AttackSpeedCheck(plugin));
        add(new HitboxCheck(plugin));
        add(new AimAssistCheck(plugin));
        add(new NoSwingCheck(plugin));
        add(new InvalidAttackCheck(plugin));
        add(new CriticalsCheck(plugin));
        add(new TBotCheck(plugin));

        // ── Velocity / knockback ──
        add(new VelocityCheck(plugin));

        // ── Listener checks (block / inventory / misc) ──
        add(new ScaffoldCheck(plugin));
        add(new BurrowCheck(plugin));
        add(new AirPlaceCheck(plugin));
        add(new SurroundCheck(plugin));
        add(new SelfTrapCheck(plugin));
        add(new CrystalAuraCheck(plugin));
        add(new BowAimbotCheck(plugin));
        add(new AutoTotemCheck(plugin));
        add(new AutoArmorCheck(plugin));
        add(new AutoWeaponCheck(plugin));
        add(new OffhandCheck(plugin));
        add(new ChestSwapCheck(plugin));
        add(new AutoEatCheck(plugin));
        add(new AutoReplenishCheck(plugin));
        add(new FastUseCheck(plugin));
        add(new RegenCheck(plugin));
        add(new AutoFishCheck(plugin));
        add(new ExpThrowerCheck(plugin));
        add(new InventoryHackCheck(plugin));
        add(new InventoryMoveCheck(plugin));
        add(new AutoToolCheck(plugin));
        add(new FastPlaceCheck(plugin));
        add(new BlockReachCheck(plugin));
        add(new SpeedMineCheck(plugin));
        add(new NukerCheck(plugin));
        add(new FastBreakCheck(plugin));
        add(new AutoSmelterCheck(plugin));

        for (ListenerCheck lc : listenerChecks) {
            plugin.getServer().getPluginManager().registerEvents(lc, plugin);
        }

        plugin.getLogger().info("Registered " + byName.size() + " checks ("
                + movementChecks.size() + " movement, "
                + combatChecks.size() + " combat, "
                + listenerChecks.size() + " event).");
    }

    private void add(Check check) {
        byName.put(check.getName(), check);
        if (check instanceof MovementCheck mc) movementChecks.add(mc);
        else if (check instanceof CombatCheck cc) combatChecks.add(cc);
        else if (check instanceof ListenerCheck lc) listenerChecks.add(lc);
    }

    public List<MovementCheck> movement() { return movementChecks; }
    public List<CombatCheck> combat() { return combatChecks; }
    public Check get(String name) { return byName.get(name); }
    public Map<String, Check> all() { return byName; }
}
