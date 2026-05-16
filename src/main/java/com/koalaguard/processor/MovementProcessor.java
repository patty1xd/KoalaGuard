package com.koalaguard.processor;

import com.koalaguard.KoalaGuard;
import com.koalaguard.check.MovementCheck;
import com.koalaguard.data.PlayerData;
import com.koalaguard.util.LocationUtil;
import com.koalaguard.util.MathUtil;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.potion.PotionEffectType;

/**
 * The single movement pipeline. It computes the shared movement + rotation
 * model into {@link PlayerData} ONCE per tick, then drives every movement
 * check. Replaces ~13 individual PlayerMoveEvent listeners.
 */
public final class MovementProcessor implements Listener {

    private final KoalaGuard plugin;
    private static final int ROT_SAMPLES = 40;

    public MovementProcessor(KoalaGuard plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Location to = event.getTo();
        Location from = event.getFrom();
        if (to == null) return;

        Player player = event.getPlayer();
        PlayerData data = plugin.getDataManager().get(player);
        if (data == null) return;

        boolean posChanged = from.getX() != to.getX() || from.getY() != to.getY() || from.getZ() != to.getZ();
        boolean rotChanged = from.getYaw() != to.getYaw() || from.getPitch() != to.getPitch();

        // ── rotation model (always, even rotation-only packets) ──
        data.lastYaw = data.yaw;
        data.lastPitch = data.pitch;
        data.yaw = to.getYaw();
        data.pitch = to.getPitch();
        data.lastDeltaYaw = data.deltaYaw;
        data.lastDeltaPitch = data.deltaPitch;
        data.deltaYaw = Math.abs(MathUtil.wrapAngle(data.yaw - data.lastYaw));
        data.deltaPitch = Math.abs(data.pitch - data.lastPitch);
        if (rotChanged) {
            push(data.yawSamples, data.deltaYaw);
            push(data.pitchSamples, data.deltaPitch);
        }
        data.rotationChanged = rotChanged;
        data.positionChanged = posChanged;

        if (!posChanged) return;

        // ── positional model ──
        data.from = from;
        data.to = to;
        data.lastDeltaX = data.deltaX;
        data.lastDeltaY = data.deltaY;
        data.lastDeltaZ = data.deltaZ;
        data.lastDeltaXZ = data.deltaXZ;
        data.deltaX = to.getX() - from.getX();
        data.deltaY = to.getY() - from.getY();
        data.deltaZ = to.getZ() - from.getZ();
        data.deltaXZ = Math.hypot(data.deltaX, data.deltaZ);
        data.accelerationXZ = data.deltaXZ - data.lastDeltaXZ;

        data.lastOnGround = data.onGround;
        data.clientGround = player.isOnGround();
        data.serverGround = LocationUtil.isOnGround(player);
        data.onGround = data.clientGround || data.serverGround;
        data.nearGround = data.serverGround;

        if (data.onGround) {
            data.groundTicks++;
            data.airTicks = 0;
            data.sinceGroundTicks = 0;
        } else {
            data.airTicks++;
            data.groundTicks = 0;
            data.sinceGroundTicks++;
        }

        // ── environment / exemption flags (computed once) ──
        data.exemptFlying = player.isFlying() || player.getAllowFlight();
        data.exemptVehicle = player.isInsideVehicle();
        data.exemptGliding = player.isGliding();
        data.exemptClimbing = player.isClimbing();
        data.exemptLiquid = player.isInWater() || player.isInLava() || LocationUtil.inLiquid(player);
        data.exemptRiptide = player.isRiptiding();
        data.exemptLevitation = player.hasPotionEffect(PotionEffectType.LEVITATION);
        data.exemptSlowFalling = player.hasPotionEffect(PotionEffectType.SLOW_FALLING);

        if (player.getGameMode() == GameMode.CREATIVE || player.getGameMode() == GameMode.SPECTATOR) return;

        // ── drive movement checks ──
        for (MovementCheck check : plugin.getCheckManager().movement()) {
            try {
                if (check.isEnabled()) check.handle(data, player);
            } catch (Throwable t) {
                plugin.getLogger().warning("Check " + check.getName() + " error: " + t);
            }
        }
    }

    private void push(java.util.Deque<Float> dq, float v) {
        dq.addLast(v);
        while (dq.size() > ROT_SAMPLES) dq.removeFirst();
    }
}
