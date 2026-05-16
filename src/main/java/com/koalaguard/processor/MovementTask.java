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
 * Drains the per-packet move queue every server tick and replays each frame
 * as ONE client movement tick. This is the critical correctness fix: deltas
 * are computed packet-to-packet (exactly how the client integrates physics),
 * never sampled at the server-tick boundary — so a tick with no move packet
 * is not a phantom "0 motion" (Fly FP) and a tick with two packets is not a
 * phantom "2× speed" (Speed FP). Same approach Grim/Vulcan use.
 */
public final class MovementTask extends BukkitRunnable {

    private final KoalaGuard plugin;
    private static final int MAX_FRAMES_PER_TICK = 22;

    public MovementTask(KoalaGuard plugin) {
        this.plugin = plugin;
    }

    @Override
    public void run() {
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            PlayerData d = plugin.getDataManager().get(player);
            if (d == null) continue;

            if (d.setbackPending) {           // mid-lagback: discard in-flight frames
                d.moveQueue.clear();
                d.moveInit = false;
                continue;
            }

            boolean spectatorish = player.getGameMode() == GameMode.CREATIVE
                    || player.getGameMode() == GameMode.SPECTATOR;

            // environment flags (current state is fine — doesn't change sub-tick)
            d.exemptFlying = player.isFlying() || player.getAllowFlight();
            d.exemptVehicle = player.isInsideVehicle();
            d.exemptGliding = player.isGliding();
            d.exemptClimbing = player.isClimbing();
            d.exemptLiquid = player.isInWater() || player.isInLava() || LocationUtil.inLiquid(player);
            d.exemptRiptide = player.isRiptiding();
            d.exemptLevitation = player.hasPotionEffect(PotionEffectType.LEVITATION);
            d.exemptSlowFalling = player.hasPotionEffect(PotionEffectType.SLOW_FALLING);

            int processed = 0;
            double[] f;
            while ((f = d.moveQueue.poll()) != null) {
                if (++processed > MAX_FRAMES_PER_TICK) { d.moveQueue.clear(); break; }

                double x = f[0], y = f[1], z = f[2];
                float yaw = (float) f[3], pitch = (float) f[4];
                boolean cg = f[5] == 1.0;

                if (!d.moveInit) {
                    d.prevX = x; d.prevY = y; d.prevZ = z;
                    d.prevYaw = yaw; d.prevPitch = pitch;
                    d.moveInit = true;
                    continue;
                }

                double dx = x - d.prevX, dy = y - d.prevY, dz = z - d.prevZ;

                // ungraced teleport / world move — reset baseline, don't evaluate
                if (Math.abs(dx) > 8 || Math.abs(dy) > 8 || Math.abs(dz) > 8) {
                    d.prevX = x; d.prevY = y; d.prevZ = z;
                    d.prevYaw = yaw; d.prevPitch = pitch;
                    continue;
                }

                d.lastDeltaX = d.deltaX; d.lastDeltaY = d.deltaY; d.lastDeltaZ = d.deltaZ;
                d.lastDeltaXZ = d.deltaXZ;
                d.deltaX = dx; d.deltaY = dy; d.deltaZ = dz;
                d.deltaXZ = Math.hypot(dx, dz);
                d.accelerationXZ = d.deltaXZ - d.lastDeltaXZ;
                d.positionChanged = true;

                d.lastYaw = d.yaw; d.lastPitch = d.pitch;
                d.yaw = yaw; d.pitch = pitch;
                d.lastDeltaYaw = d.deltaYaw; d.lastDeltaPitch = d.deltaPitch;
                d.deltaYaw = Math.abs(MathUtil.wrapAngle(yaw - d.prevYaw));
                d.deltaPitch = Math.abs(pitch - d.prevPitch);

                d.lastOnGround = d.onGround;
                d.clientGround = cg;
                d.serverGround = LocationUtil.isOnGround(player.getWorld(), x, y, z);
                d.onGround = cg || d.serverGround;
                d.nearGround = d.serverGround;
                if (d.onGround) { d.groundTicks++; d.airTicks = 0; d.sinceGroundTicks = 0; }
                else { d.airTicks++; d.groundTicks = 0; d.sinceGroundTicks++; }

                if (!spectatorish) {
                    for (MovementCheck check : plugin.getCheckManager().movement()) {
                        try {
                            if (check.isEnabled()) check.handle(d, player);
                        } catch (Throwable t) {
                            plugin.getLogger().warning("Check " + check.getName() + " error: " + t);
                        }
                    }
                    plugin.getSetbackManager().markValid(d, player, d.onGround || d.nearGround);
                    if (d.setbackPending) { d.moveQueue.clear(); break; }
                }

                d.prevX = x; d.prevY = y; d.prevZ = z;
                d.prevYaw = yaw; d.prevPitch = pitch;
            }
        }
    }
}
