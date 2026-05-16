package com.koalaguard.engine;

import com.koalaguard.KoalaGuard;
import com.koalaguard.data.PlayerData;
import com.koalaguard.engine.check.CheckContext;
import com.koalaguard.engine.check.SimCheck;
import com.koalaguard.engine.packet.CapturedPacket;
import com.koalaguard.engine.packet.PacketKind;
import com.koalaguard.engine.sim.CollisionEngine;
import com.koalaguard.engine.sim.PhysicsSimulator;
import com.koalaguard.engine.sim.SimResult;
import com.koalaguard.engine.state.InventoryState;
import com.koalaguard.engine.state.PlayerState;
import com.koalaguard.engine.state.PositionFrame;
import com.koalaguard.util.LocationUtil;
import com.koalaguard.util.MathUtil;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;

/**
 * The heart of the engine. Every server tick, for every player, it:
 *   1. refreshes server-authoritative environment + inventory mirror,
 *   2. drains the captured packet stream and replays it ONE client movement
 *      tick at a time, rebuilding the tick-indexed state machine + log,
 *   3. runs the simulator and the per-frame checks against each frame,
 *   4. runs the per-tick (transition/aggregate) checks once.
 *
 * It is continuous, not event-driven: a tick with no packets still advances
 * the model and still runs the transition checks.
 */
public final class EngineTask extends BukkitRunnable {

    private static final int MAX_FRAMES_PER_TICK = 22;

    private final KoalaGuard plugin;

    public EngineTask(KoalaGuard plugin) {
        this.plugin = plugin;
    }

    @Override
    public void run() {
        EngineManager em = plugin.getEngine();
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            PlayerData d = plugin.getDataManager().get(player);
            if (d == null) continue;
            PlayerState s = d.engine;

            if (d.setbackPending) {              // mid-lagback: discard in-flight
                s.intake.clear();
                s.moveInit = false;
                continue;
            }

            refreshEnvironment(player, s);
            refreshInventory(player, s);

            boolean evaluate = !s.exSpectator && !s.exDead;

            int processed = 0;
            CapturedPacket p;
            while ((p = s.intake.poll()) != null) {
                p.tickIndex = s.tick;
                p.stateRef = s.current;
                s.log.append(p);

                if (p.kind == PacketKind.MOVEMENT) {
                    if (++processed > MAX_FRAMES_PER_TICK) { s.intake.clear(); break; }
                    if (handleMovement(player, d, s, p, evaluate)) break; // setback
                } else {
                    handleAuxiliary(s, p);
                }
            }

            // Per-tick transition / aggregate checks (continuous — even with
            // zero packets this tick).
            if (evaluate && !d.setbackPending) {
                SimResult sim = (s.previous != null && s.current != null)
                        ? PhysicsSimulator.simulate(d, player) : new SimResult();
                CheckContext ctx = new CheckContext(plugin, d, player, s, sim, s.tick);
                for (SimCheck c : em.tickChecks()) {
                    if (!c.isEnabled()) continue;
                    try { c.onTick(ctx); }
                    catch (Throwable t) {
                        plugin.getLogger().warning("Check " + c.getName() + " error: " + t);
                    }
                }
            }
        }
    }

    /** @return true if a setback was requested and the batch must abort. */
    private boolean handleMovement(Player player, PlayerData d, PlayerState s,
                                   CapturedPacket p, boolean evaluate) {
        float yaw   = p.hasRot ? p.yaw   : s.prevYaw;
        float pitch = p.hasRot ? p.pitch : s.prevPitch;

        if (p.hasRot && !p.hasPos) {                 // rotation-only packet
            s.pushRotation(yaw, pitch);
            s.prevYaw = yaw; s.prevPitch = pitch;
            return false;
        }
        if (!p.hasPos) return false;

        double x = p.x, y = p.y, z = p.z;
        if (!s.moveInit) {
            s.prevX = x; s.prevY = y; s.prevZ = z;
            s.prevYaw = yaw; s.prevPitch = pitch;
            s.moveInit = true;
            return false;
        }

        double dx = x - s.prevX, dy = y - s.prevY, dz = z - s.prevZ;
        if (Math.abs(dx) > 8 || Math.abs(dy) > 8 || Math.abs(dz) > 8) {
            // ungraced teleport / world move — reset baseline, don't evaluate
            s.prevX = x; s.prevY = y; s.prevZ = z;
            s.prevYaw = yaw; s.prevPitch = pitch;
            return false;
        }

        long tick = ++s.tick;
        float dYaw = Math.abs(MathUtil.wrapAngle(yaw - s.prevYaw));
        float dPitch = Math.abs(pitch - s.prevPitch);

        PositionFrame f = new PositionFrame(tick, x, y, z,
                s.prevX, s.prevY, s.prevZ, yaw, pitch, dYaw, dPitch, p.onGround);

        double h = player.isSneaking() ? 1.5 : 1.8;
        f.simGround = CollisionEngine.supported(player.getWorld(), x, y, z, h);
        f.groundSlipperiness = CollisionEngine.slipperiness(player.getWorld(), x, y, z);
        f.insideSolid = CollisionEngine.insideSolid(player.getWorld(), x, y, z, h);
        boolean ground = f.simGround || p.onGround;

        s.pushFrame(f);
        s.pushRotation(yaw, pitch);

        if (ground) { s.groundTicks++; s.airTicks = 0; s.sinceGroundTicks = 0; }
        else { s.airTicks++; s.groundTicks = 0; s.sinceGroundTicks++; }

        if (evaluate) {
            SimResult sim = PhysicsSimulator.simulate(d, player);
            CheckContext ctx = new CheckContext(plugin, d, player, s, sim, tick);
            for (SimCheck c : plugin.getEngine().frameChecks()) {
                if (!c.isEnabled()) continue;
                try { c.onTick(ctx); }
                catch (Throwable t) {
                    plugin.getLogger().warning("Check " + c.getName() + " error: " + t);
                }
            }
            plugin.getSetbackManager().markValid(d, player,
                    ground || CollisionEngine.nearGround(player.getWorld(), x, y, z, h));
            if (d.setbackPending) {
                s.intake.clear();
                return true;
            }
        }

        s.prevX = x; s.prevY = y; s.prevZ = z;
        s.prevYaw = yaw; s.prevPitch = pitch;
        return false;
    }

    /** Drives the inventory / combat / window transition sub-machines. */
    private void handleAuxiliary(PlayerState s, CapturedPacket p) {
        InventoryState inv = s.inv;
        switch (p.kind) {
            case INTERACT_ENTITY -> {
                String act = String.valueOf(p.objA);
                if (act.contains("ATTACK")) {
                    s.combat.lastAttackEntityId = p.intA;
                    s.combat.lastAttackTick = s.tick;
                }
            }
            case ANIMATION -> s.combat.lastSwingTick = s.tick;
            case HELD_ITEM -> inv.heldSlot = p.intA;
            case CLICK_WINDOW -> { /* used by InventoryChainCheck via the log */ }
            case CLOSE_WINDOW -> {
                inv.containerOpen = false;
                inv.windowClosedTick = s.tick;
                inv.openWindowId = -1;
            }
            default -> { }
        }
    }

    private void refreshEnvironment(Player player, PlayerState s) {
        s.exFlying = player.isFlying() || player.getAllowFlight();
        s.exVehicle = player.isInsideVehicle();
        s.exGliding = player.isGliding();
        s.exClimbing = player.isClimbing();
        s.exLiquid = player.isInWater() || player.isInLava() || LocationUtil.inLiquid(player);
        s.exRiptide = player.isRiptiding();
        s.exLevitation = player.hasPotionEffect(PotionEffectType.LEVITATION);
        s.exSlowFalling = player.hasPotionEffect(PotionEffectType.SLOW_FALLING);
        s.exDead = player.isDead() || player.getHealth() <= 0.0;
        s.exSpectator = player.getGameMode() == GameMode.CREATIVE
                || player.getGameMode() == GameMode.SPECTATOR;
    }

    /**
     * Server-authoritative inventory mirror. Detects a totem leaving a hand as
     * a STATE TRANSITION (consume), which AutoTotemCheck later validates
     * against the packet stream — no stopwatch anywhere.
     */
    private void refreshInventory(Player player, PlayerState s) {
        PlayerInventory pi = player.getInventory();
        InventoryState inv = s.inv;

        Material newMain = pi.getItemInMainHand().getType();
        Material newOff  = pi.getItemInOffHand().getType();
        int newOffCount  = pi.getItemInOffHand().getAmount();
        int newMainCount = pi.getItemInMainHand().getAmount();
        Material newCursor = player.getItemOnCursor().getType();

        boolean offWasTotem  = inv.offHand == Material.TOTEM_OF_UNDYING;
        boolean mainWasTotem = inv.mainHand == Material.TOTEM_OF_UNDYING;

        if (newMain != inv.mainHand) inv.mainHandChangedTick = s.tick;
        if (newOff  != inv.offHand)  inv.offHandChangedTick  = s.tick;

        // Totem consumed out of a hand → arm the transition validator.
        boolean offConsumed  = offWasTotem  && newOff  != Material.TOTEM_OF_UNDYING;
        boolean mainConsumed = mainWasTotem && newMain != Material.TOTEM_OF_UNDYING;
        // Or a single totem in a stack was eaten (count dropped, still totem).
        boolean offDecremented = offWasTotem && newOff == Material.TOTEM_OF_UNDYING
                && newOffCount < inv.offHandCount;

        if ((offConsumed || mainConsumed) && !offDecremented) {
            inv.totemConsumedTick = s.tick;
            inv.awaitingTotemTransition = true;
        }

        inv.mainHand = newMain;
        inv.offHand = newOff;
        inv.cursor = newCursor;
        inv.offHandCount = newOffCount;
        inv.mainHandCount = newMainCount;
        inv.heldSlot = pi.getHeldItemSlot();
    }
}
