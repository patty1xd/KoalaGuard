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
                // Selective drain: drop MOVEMENT (would undo the teleport),
                // PRESERVE aux packets so detection (AutoTotem / clicks /
                // attacks / inventory) survives a rubber-band. The blanket
                // intake.clear() pattern was eating those — documented bug.
                int kept = 0;
                java.util.ArrayList<CapturedPacket> survivors = new java.util.ArrayList<>();
                CapturedPacket cp;
                while ((cp = s.intake.poll()) != null) {
                    if (cp.kind != PacketKind.MOVEMENT) {
                        survivors.add(cp);
                        kept++;
                    }
                }
                for (CapturedPacket k : survivors) s.intake.offer(k);
                s.moveInit = false;
                continue;
            }

            refreshEnvironment(player, s);
            refreshInventory(player, s);
            // Combat lag-compensation: snapshot nearby hitboxes so aim checks
            // can rewind the victim to the attack instant.
            s.targets.snapshot(System.nanoTime(),
                    player.getNearbyEntities(14, 14, 14));

            boolean evaluate = !s.exSpectator && !s.exDead;

            s.framesThisTick = 0;
            int processed = 0;
            boolean moveCap = false;
            CapturedPacket p;
            while ((p = s.intake.poll()) != null) {
                p.tickIndex = s.tick;
                p.stateRef = s.current;
                s.log.append(p);

                if (p.kind == PacketKind.MOVEMENT) {
                    // Cap reconstructed movement frames per tick, but DO NOT
                    // clear the intake — that used to delete every queued
                    // attack / click / totem-move behind a movement burst
                    // (lagswitch/blink), silently disabling combat & inventory
                    // detection. The packet is still logged (Blink sees the
                    // burst); we just skip frame reconstruction past the cap
                    // and keep draining auxiliary packets.
                    if (moveCap || ++processed > MAX_FRAMES_PER_TICK) {
                        moveCap = true;
                        continue;
                    }
                    if (handleMovement(player, d, s, p, evaluate)) break; // setback
                } else {
                    handleAuxiliary(s, p);
                }
            }

            // Per-tick transition / aggregate checks (continuous — even with
            // zero packets this tick).
            if (evaluate && !d.setbackPending) {
                // Reconstruct any completed totem cycle BEFORE the checks read
                // it (packet-precise, version-independent).
                com.koalaguard.engine.totem.TotemCycleProcessor.process(d, s);

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

        // ── Clip detection (.vclip / .hclip / phase-teleport) ──
        // Runs BEFORE the >8 reset (which is exactly why big vclips were never
        // caught — they looked like a legit teleport and were discarded). A
        // single client move whose straight path passes THROUGH solid blocks,
        // with no server-initiated teleport (Paper's internal anti-move
        // correction does NOT fire PlayerTeleportEvent, so lastTeleportMs is
        // only set by REAL pearls/commands/portals), is a clip — conclusive.
        if (evaluate && !d.setbackPending) {
            double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
            long now = System.currentTimeMillis();
            boolean graced = now - d.lastTeleportMs   < 1500
                    || now - d.lastRespawnMs    < 2500
                    || now - d.lastWorldChangeMs < 4000
                    || now - d.lastVelocityMs   < 1500
                    || now - d.joinMs           < 3500;
            boolean special = s.exVehicle || s.exGliding || s.exRiptide
                    || s.exLevitation || s.exWeb || s.exClimbing;
            double minDist = plugin.getConfig().getDouble("checks.clip.min-dist", 1.0);
            if (dist > minDist && !graced && !special) {
                var w = player.getWorld();
                // Clip-dedicated scan: no endpoint-cell skip, 0.1 step, full
                // body height — catches medium (10–20 block) clips the old
                // rayBlocked-based test silently missed.
                boolean through =
                        CollisionEngine.solidOnSegment(w, s.prevX, s.prevY, s.prevZ, x, y, z);
                if (through) {
                    d.clipSeq++;
                    d.clipDetail = String.format(
                            "moved %.1f blocks through solid (dx=%.1f dy=%.1f dz=%.1f), no server teleport",
                            dist, dx, dy, dz);
                    s.prevX = x; s.prevY = y; s.prevZ = z;   // re-baseline
                    s.prevYaw = yaw; s.prevPitch = pitch;
                    return false;
                }
            }
        }

        if (Math.abs(dx) > 8 || Math.abs(dy) > 8 || Math.abs(dz) > 8) {
            // A >8-block single-tick displacement. This used to ALWAYS just
            // reset baseline and skip evaluation — which is exactly how
            // .vclip/.tp/backstab-teleport variants evaded ALL checks (the
            // move was eaten before any frame check ever saw it).
            //
            // Fix: if NO server-initiated teleport / pearl / velocity grace
            // is active, treat the move itself as a clip event (open-air
            // teleport) and stamp clipSeq so ClipCheck rubber-bands. The
            // path-through-solid case (CollisionEngine.solidOnSegment above)
            // already stamps for narrow clips through walls — this catches
            // the bigger free-space teleport that doesn't traverse solid.
            if (evaluate && !d.setbackPending) {
                long now = System.currentTimeMillis();
                boolean graced = now - d.lastTeleportMs    < 1500
                              || now - d.lastRespawnMs     < 2500
                              || now - d.lastWorldChangeMs < 4000
                              || now - d.lastVelocityMs    < 1500
                              || now - d.joinMs            < 3500;
                // Include exWeb / exClimbing the same way the under-8 clip
                // path does — tridenting out of a cobweb or boosting off a
                // ladder can produce a single >8 displacement and was being
                // flagged as a phantom clip.
                boolean specialMove = s.exVehicle || s.exGliding || s.exRiptide
                                   || s.exLevitation || s.exWeb || s.exClimbing;
                if (!graced && !specialMove) {
                    // Two paths: if the move went THROUGH solid → clip; if
                    // not → clickTp (free-air teleport). Both rubber-band.
                    boolean throughSolid = CollisionEngine.solidOnSegment(
                            player.getWorld(), s.prevX, s.prevY, s.prevZ, x, y, z);
                    if (throughSolid) {
                        d.clipSeq++;
                        d.clipDetail = String.format(
                                "ungraced >8-block teleport through solid (dx=%.1f dy=%.1f dz=%.1f)",
                                dx, dy, dz);
                    } else {
                        d.clickTpSeq++;
                        d.clickTpDetail = String.format(
                                "ungraced >8-block teleport (dx=%.1f dy=%.1f dz=%.1f)",
                                dx, dy, dz);
                    }
                }
            }
            // reset baseline so reconstruction can resume on the next packet
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
        boolean near = CollisionEngine.nearGround(player.getWorld(), x, y, z, h);
        f.groundSlipperiness = CollisionEngine.slipperiness(player.getWorld(), x, y, z);
        f.insideSolid = CollisionEngine.insideSolid(player.getWorld(), x, y, z, h);
        // Client onGround is only trusted when server geometry corroborates it.
        boolean ground = f.simGround || (p.onGround && near);

        s.pushFrame(f);
        s.framesThisTick++;
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
            plugin.getSetbackManager().markValid(d, player, f.simGround || near);
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
                    s.combat.lastAttackNanos = p.recvNanos;
                    s.combat.lastAttackHasRot = p.hasRot;
                    if (p.hasRot) {
                        s.combat.lastAttackYaw = p.yaw;
                        s.combat.lastAttackPitch = p.pitch;
                    }
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
        s.exWeb = LocationUtil.inSpecialMovementBlock(player);
        if (s.exWeb) s.lastSpecialBlockTick = s.tick;
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

        if (newMain != inv.mainHand) inv.mainHandChangedTick = s.tick;
        if (newOff  != inv.offHand)  inv.offHandChangedTick  = s.tick;

        // NOTE: the totem pop is NOT detected here. A once-per-tick mirror
        // misses a sub-tick consume→refill (exactly what a fast autototem
        // does), so the pop is driven by EntityResurrectEvent in
        // BukkitStateListener instead. This method only mirrors hand state.

        inv.mainHand = newMain;
        inv.offHand = newOff;
        inv.cursor = newCursor;
        inv.offHandCount = newOffCount;
        inv.mainHandCount = newMainCount;
        inv.heldSlot = pi.getHeldItemSlot();
    }
}
