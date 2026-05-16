package com.koalaguard.processor;

import com.koalaguard.KoalaGuard;
import com.koalaguard.check.MovementCheck;
import com.koalaguard.data.PlayerData;
import com.koalaguard.util.LocationUtil;
import com.koalaguard.util.MathUtil;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;

/**
 * Runs once per server tick on the main thread. It rebuilds the movement
 * model from the latest packet-accurate position/rotation captured by
 * {@link PacketProcessor}, then drives every movement check. Because the
 * positions come from the client's own packets (not lossy Bukkit move
 * events) Speed/Timer/Fly/NoSlow are both accurate and FP-resistant.
 */
public final class MovementTask extends BukkitRunnable {

    private final KoalaGuard plugin;

    public MovementTask(KoalaGuard plugin) {
        this.plugin = plugin;
    }

    @Override
    public void run() {
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            PlayerData d = plugin.getDataManager().get(player);
            if (d == null || !d.pHasPos) continue;

            double sx = d.pX, sy = d.pY, sz = d.pZ;
            float syaw = d.pYaw, spitch = d.pPitch;

            double[] last = d.obj("mv$last");
            if (last == null) {
                d.setObj("mv$last", new double[]{sx, sy, sz});
                d.lastYaw = syaw; d.lastPitch = spitch;
                continue;
            }

            // Mid-lagback: don't evaluate physics on a position in flux.
            if (d.setbackPending) { last[0] = sx; last[1] = sy; last[2] = sz; continue; }

            // ── rotation model (packet-accurate) ──
            d.lastYaw = d.yaw; d.lastPitch = d.pitch;
            d.yaw = syaw; d.pitch = spitch;
            d.lastDeltaYaw = d.deltaYaw; d.lastDeltaPitch = d.deltaPitch;
            d.deltaYaw = Math.abs(MathUtil.wrapAngle(d.yaw - d.lastYaw));
            d.deltaPitch = Math.abs(d.pitch - d.lastPitch);
            if (d.deltaYaw > 0.0001f || d.deltaPitch > 0.0001f) {
                push(d.yawSamples, d.deltaYaw);
                push(d.pitchSamples, d.deltaPitch);
                d.rotationChanged = true;
            } else d.rotationChanged = false;

            // ── positional model ──
            d.lastDeltaX = d.deltaX; d.lastDeltaY = d.deltaY; d.lastDeltaZ = d.deltaZ;
            d.lastDeltaXZ = d.deltaXZ;
            d.deltaX = sx - last[0];
            d.deltaY = sy - last[1];
            d.deltaZ = sz - last[2];
            d.deltaXZ = Math.hypot(d.deltaX, d.deltaZ);
            d.accelerationXZ = d.deltaXZ - d.lastDeltaXZ;
            d.positionChanged = Math.abs(d.deltaX) > 1e-6 || Math.abs(d.deltaY) > 1e-6 || Math.abs(d.deltaZ) > 1e-6;

            d.lastOnGround = d.onGround;
            d.clientGround = d.pOnGround;
            d.serverGround = LocationUtil.isOnGround(player);
            d.onGround = d.clientGround || d.serverGround;
            d.nearGround = d.serverGround;
            if (d.onGround) { d.groundTicks++; d.airTicks = 0; d.sinceGroundTicks = 0; }
            else { d.airTicks++; d.groundTicks = 0; d.sinceGroundTicks++; }

            // ── environment flags ──
            d.exemptFlying = player.isFlying() || player.getAllowFlight();
            d.exemptVehicle = player.isInsideVehicle();
            d.exemptGliding = player.isGliding();
            d.exemptClimbing = player.isClimbing();
            d.exemptLiquid = player.isInWater() || player.isInLava() || LocationUtil.inLiquid(player);
            d.exemptRiptide = player.isRiptiding();
            d.exemptLevitation = player.hasPotionEffect(PotionEffectType.LEVITATION);
            d.exemptSlowFalling = player.hasPotionEffect(PotionEffectType.SLOW_FALLING);

            last[0] = sx; last[1] = sy; last[2] = sz;

            if (player.getGameMode() == GameMode.CREATIVE || player.getGameMode() == GameMode.SPECTATOR)
                continue;

            for (MovementCheck check : plugin.getCheckManager().movement()) {
                try {
                    if (check.isEnabled()) check.handle(d, player);
                } catch (Throwable t) {
                    plugin.getLogger().warning("Check " + check.getName() + " error: " + t);
                }
            }

            // Advance the lagback anchor only on a supported, accepted position.
            plugin.getSetbackManager().markValid(d, player, d.onGround || d.nearGround);
        }
    }

    private void push(java.util.Deque<Float> dq, float v) {
        dq.addLast(v);
        while (dq.size() > 40) dq.removeFirst();
    }
}
