package com.koalaguard.engine;

import com.koalaguard.KoalaGuard;
import com.koalaguard.engine.check.SimCheck;
import com.koalaguard.engine.checks.combat.AimA;
import com.koalaguard.engine.checks.combat.AimB;
import com.koalaguard.engine.checks.combat.AimC;
import com.koalaguard.engine.checks.combat.AntiKBSprintCheck;
import com.koalaguard.engine.checks.combat.BowAimbotCheck;
import com.koalaguard.engine.checks.combat.AimD;
import com.koalaguard.engine.checks.combat.AimE;
import com.koalaguard.engine.checks.combat.AimF;
import com.koalaguard.engine.checks.combat.AimG;
import com.koalaguard.engine.checks.combat.AimH;
import com.koalaguard.engine.checks.combat.AimI;
import com.koalaguard.engine.checks.combat.AutoClickerCheck;
import com.koalaguard.engine.checks.combat.HoneypotCheck;
import com.koalaguard.engine.checks.combat.MaceCheck;
import com.koalaguard.engine.checks.combat.MacroCheck;
import com.koalaguard.engine.checks.combat.ShieldBypassCheck;
import com.koalaguard.engine.checks.combat.CriticalsCheck;
import com.koalaguard.engine.checks.combat.HitValidationCheck;
import com.koalaguard.engine.checks.combat.ReachCheck;
import com.koalaguard.engine.checks.combat.RotationCheck;
import com.koalaguard.engine.checks.combat.VelocityCheck;
import com.koalaguard.engine.checks.inventory.AutoTotemA;
import com.koalaguard.engine.checks.inventory.AutoTotemB;
import com.koalaguard.engine.checks.inventory.AutoTotemC;
import com.koalaguard.engine.checks.inventory.AutoTotemD;
import com.koalaguard.engine.checks.inventory.AutoTotemE;
import com.koalaguard.engine.checks.inventory.AutoTotemF;
import com.koalaguard.engine.checks.inventory.BadPacketsBrand;
import com.koalaguard.engine.checks.inventory.BadPacketsDuplicate;
import com.koalaguard.engine.checks.inventory.InventoryChainCheck;
import com.koalaguard.engine.checks.movement.AirJumpCheck;
import com.koalaguard.engine.checks.movement.AntiVoidCheck;
import com.koalaguard.engine.checks.movement.BlinkCheck;
import com.koalaguard.engine.checks.movement.ClickTpCheck;
import com.koalaguard.engine.checks.movement.ClipCheck;
import com.koalaguard.engine.checks.movement.ElytraFlyCheck;
import com.koalaguard.engine.checks.movement.FastClimbCheck;
import com.koalaguard.engine.checks.movement.GroundSpoofCheck;
import com.koalaguard.engine.checks.movement.InputSanityCheck;
import com.koalaguard.engine.checks.movement.JesusCheck;
import com.koalaguard.engine.checks.movement.NoFallCheck;
import com.koalaguard.engine.checks.movement.NoSlowCheck;
import com.koalaguard.engine.checks.movement.PhaseCheck;
import com.koalaguard.engine.checks.movement.PredictionCheck;
import com.koalaguard.engine.checks.movement.SpiderCheck;
import com.koalaguard.engine.checks.movement.TimerCheck;
import com.koalaguard.engine.checks.movement.VehicleFlyCheck;
import com.koalaguard.engine.checks.player.BadPacketsSanity;
import com.koalaguard.engine.checks.player.CheatClientCheck;
import com.koalaguard.engine.checks.player.InventoryActionCheck;
import com.koalaguard.engine.checks.player.MultiTaskCheck;
import com.koalaguard.engine.checks.player.SprintHungerCheck;
import com.koalaguard.engine.checks.world.AirPlaceCheck;
import com.koalaguard.engine.checks.world.AutoWebCheck;
import com.koalaguard.engine.checks.world.FastBreakCheck;
import com.koalaguard.engine.checks.world.MultiPlaceCheck;
import com.koalaguard.engine.checks.world.NukerCheck;
import com.koalaguard.engine.checks.world.ScaffoldCheck;

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
        add(new FastClimbCheck(plugin));
        add(new AntiVoidCheck(plugin));
        add(new AirJumpCheck(plugin));
        add(new NoSlowCheck(plugin));
        add(new ClickTpCheck(plugin));
        add(new ClipCheck(plugin));
        add(new BlinkCheck(plugin));
        add(new JesusCheck(plugin));
        add(new SpiderCheck(plugin));
        add(new ElytraFlyCheck(plugin));
        add(new VehicleFlyCheck(plugin));
        add(new TimerCheck(plugin));
        add(new GroundSpoofCheck(plugin));
        add(new InputSanityCheck(plugin));

        // ── Packet sanity: protocol-illegal values rejected at netty,
        //    reported here ──
        add(new BadPacketsSanity(plugin));

        // ── Combat: reconstructed-position raytrace + aim plausibility ──
        add(new ReachCheck(plugin));
        add(new HitValidationCheck(plugin));
        add(new RotationCheck(plugin));
        add(new VelocityCheck(plugin));
        add(new AutoClickerCheck(plugin));
        add(new CriticalsCheck(plugin));

        // ── Aim family — KillAura split into independent, FP-tuned checks ──
        add(new AimA(plugin));
        add(new AimB(plugin));
        add(new AimC(plugin));
        add(new AimD(plugin));
        add(new AimE(plugin));
        add(new AimF(plugin));
        add(new AimG(plugin));
        add(new AimH(plugin));
        add(new AimI(plugin));
        add(new HoneypotCheck(plugin));
        add(new MaceCheck(plugin));
        add(new ShieldBypassCheck(plugin));
        add(new MacroCheck(plugin));
        add(new AntiKBSprintCheck(plugin));
        add(new BowAimbotCheck(plugin));

        // ── Inventory: interaction-chain state machines ──
        add(new InventoryChainCheck(plugin));

        // ── AutoTotem family (TotemGuard model) — every type is its OWN
        //    independent check reading the shared packet-precise totem cycle ──
        add(new BadPacketsBrand(plugin));
        add(new BadPacketsDuplicate(plugin));
        add(new AutoTotemA(plugin));
        add(new AutoTotemB(plugin));
        add(new AutoTotemC(plugin));
        add(new AutoTotemD(plugin));
        add(new AutoTotemE(plugin));
        add(new AutoTotemF(plugin));

        // ── Player / World ──
        add(new CheatClientCheck(plugin));
        add(new MultiTaskCheck(plugin));
        add(new InventoryActionCheck(plugin));
        add(new SprintHungerCheck(plugin));
        add(new NukerCheck(plugin));
        add(new MultiPlaceCheck(plugin));
        add(new ScaffoldCheck(plugin));
        add(new AutoWebCheck(plugin));
        add(new FastBreakCheck(plugin));
        add(new AirPlaceCheck(plugin));

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

    // ── Per-check timing telemetry ──
    // Total nanoseconds spent in each check across all players this session.
    // EngineTask wraps c.onTick(ctx) with this counter; /kg debug surfaces
    // the top offenders so admins can disable expensive checks under load.
    private final java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.LongAdder> timeNs
            = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.LongAdder> invocations
            = new java.util.concurrent.ConcurrentHashMap<>();

    public void recordCheckTiming(String name, long ns) {
        timeNs.computeIfAbsent(name, k -> new java.util.concurrent.atomic.LongAdder()).add(ns);
        invocations.computeIfAbsent(name, k -> new java.util.concurrent.atomic.LongAdder()).increment();
    }

    public Map<String, long[]> timing() {
        Map<String, long[]> out = new LinkedHashMap<>();
        for (String n : byName.keySet()) {
            long ns = timeNs.getOrDefault(n, new java.util.concurrent.atomic.LongAdder()).sum();
            long inv = invocations.getOrDefault(n, new java.util.concurrent.atomic.LongAdder()).sum();
            out.put(n, new long[]{ ns, inv });
        }
        return out;
    }

    public void resetTiming() { timeNs.clear(); invocations.clear(); }
}
