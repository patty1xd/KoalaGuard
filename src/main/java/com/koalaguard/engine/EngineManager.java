package com.koalaguard.engine;

import com.koalaguard.KoalaGuard;
import com.koalaguard.engine.check.SimCheck;
import com.koalaguard.engine.checks.combat.AutoClickerCheck;
import com.koalaguard.engine.checks.combat.CriticalsCheck;
import com.koalaguard.engine.checks.combat.CrystalAuraCheck;
import com.koalaguard.engine.checks.combat.HitValidationCheck;
import com.koalaguard.engine.checks.combat.KillAuraCheck;
import com.koalaguard.engine.checks.combat.ReachCheck;
import com.koalaguard.engine.checks.combat.RotationCheck;
import com.koalaguard.engine.checks.combat.VelocityCheck;
import com.koalaguard.engine.checks.inventory.AutoTotemCheck;
import com.koalaguard.engine.checks.inventory.InventoryChainCheck;
import com.koalaguard.engine.checks.movement.AirJumpCheck;
import com.koalaguard.engine.checks.movement.AntiVoidCheck;
import com.koalaguard.engine.checks.movement.BlinkCheck;
import com.koalaguard.engine.checks.movement.ClickTpCheck;
import com.koalaguard.engine.checks.movement.ElytraFlyCheck;
import com.koalaguard.engine.checks.movement.FastClimbCheck;
import com.koalaguard.engine.checks.movement.JesusCheck;
import com.koalaguard.engine.checks.movement.NoFallCheck;
import com.koalaguard.engine.checks.movement.NoSlowCheck;
import com.koalaguard.engine.checks.movement.PhaseCheck;
import com.koalaguard.engine.checks.movement.PredictionCheck;
import com.koalaguard.engine.checks.movement.SpiderCheck;
import com.koalaguard.engine.checks.movement.TimerCheck;
import com.koalaguard.engine.checks.player.InventoryActionCheck;
import com.koalaguard.engine.checks.player.OffhandCrashCheck;
import com.koalaguard.engine.checks.world.MultiPlaceCheck;
import com.koalaguard.engine.checks.world.NukerCheck;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Owns the engine check registry. Every check is fully independent — it only
 * reads reconstructed state and only mutates its own ViolationScore — so the
 * set can be reordered, extended or trimmed with zero coupling.
 */
public final class EngineManager {

    private final KoalaGuard plugin;
    private final List<SimCheck> frameChecks = new ArrayList<>();
    private final List<SimCheck> tickChecks  = new ArrayList<>();
    private final Map<String, SimCheck> byName = new LinkedHashMap<>();

    public EngineManager(KoalaGuard plugin) {
        this.plugin = plugin;
    }

    public void registerAll() {
        // ── Movement: server-authoritative physics divergence ──
        add(new PredictionCheck(plugin));
        add(new PhaseCheck(plugin));
        add(new NoFallCheck(plugin));
        add(new TimerCheck(plugin));
        add(new FastClimbCheck(plugin));
        add(new AntiVoidCheck(plugin));
        add(new AirJumpCheck(plugin));
        add(new NoSlowCheck(plugin));
        add(new ClickTpCheck(plugin));
        add(new BlinkCheck(plugin));
        add(new JesusCheck(plugin));
        add(new SpiderCheck(plugin));
        add(new ElytraFlyCheck(plugin));

        // ── Combat: reconstructed-position raytrace + aim plausibility ──
        add(new ReachCheck(plugin));
        add(new HitValidationCheck(plugin));
        add(new RotationCheck(plugin));
        add(new VelocityCheck(plugin));
        add(new AutoClickerCheck(plugin));
        add(new KillAuraCheck(plugin));
        add(new CrystalAuraCheck(plugin));
        add(new CriticalsCheck(plugin));

        // ── Inventory: interaction-chain state machines ──
        add(new InventoryChainCheck(plugin));
        add(new AutoTotemCheck(plugin));

        // ── Player / World ──
        add(new InventoryActionCheck(plugin));
        add(new OffhandCrashCheck(plugin));
        add(new NukerCheck(plugin));
        add(new MultiPlaceCheck(plugin));

        plugin.getLogger().info("KoalaGuard engine: registered " + byName.size()
                + " checks (" + frameChecks.size() + " per-frame, "
                + tickChecks.size() + " per-tick).");
    }

    private void add(SimCheck c) {
        byName.put(c.getName(), c);
        if (c.stage() == SimCheck.Stage.FRAME) frameChecks.add(c);
        else tickChecks.add(c);
    }

    public List<SimCheck> frameChecks() { return frameChecks; }
    public List<SimCheck> tickChecks()  { return tickChecks; }
    public Map<String, SimCheck> all()  { return byName; }
    public SimCheck get(String name)    { return byName.get(name); }

    public void cleanup(UUID uuid) {
        for (SimCheck c : byName.values()) c.cleanup(uuid);
    }
}
