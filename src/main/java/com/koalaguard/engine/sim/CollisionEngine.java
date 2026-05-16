package com.koalaguard.engine.sim;

import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.util.BoundingBox;

/**
 * Server-authoritative world collision. Reads blocks via Bukkit (MAIN THREAD
 * ONLY) and answers the geometric questions the simulator/checks need:
 * is the box supported, is it intersecting solid geometry (phase), and what is
 * the slipperiness under the feet (ice momentum is modelled, not hard-coded).
 *
 * Block collision uses {@link Block#getBoundingBox()} / {@link Block#isPassable()}
 * — robust Bukkit API that never throws on modded/odd blocks. We only ever act
 * on PERSISTENT geometric contradictions, so the slab/stairs outer-box
 * approximation cannot produce a false ban.
 */
public final class CollisionEngine {

    private CollisionEngine() {}

    public static boolean collidable(Block b) {
        if (b == null) return false;
        Material m = b.getType();
        if (m.isAir() || b.isLiquid()) return false;
        if (b.isPassable()) return false;
        BoundingBox bb = b.getBoundingBox();
        return bb != null && bb.getVolume() > 0;
    }

    /** A collidable block within 0.001..maxDrop below the player's feet. */
    public static boolean supported(World w, double x, double y, double z, double height) {
        if (w == null) return true;
        AABB feet = new AABB(x - 0.3, y - 0.001, z - 0.3, x + 0.3, y, z + 0.3)
                .expand(0, 0.0, 0);
        int minX = (int) Math.floor(feet.minX), maxX = (int) Math.floor(feet.maxX);
        int minZ = (int) Math.floor(feet.minZ), maxZ = (int) Math.floor(feet.maxZ);
        int by = (int) Math.floor(y - 0.02);
        for (int bx = minX; bx <= maxX; bx++) {
            for (int bz = minZ; bz <= maxZ; bz++) {
                Block b = w.getBlockAt(bx, by, bz);
                if (!collidable(b)) continue;
                BoundingBox box = b.getBoundingBox();
                if (box.getMaxY() >= y - 0.5 && box.getMaxY() <= y + 0.001
                        && box.getMaxX() > feet.minX && box.getMinX() < feet.maxX
                        && box.getMaxZ() > feet.minZ && box.getMinZ() < feet.maxZ) {
                    return true;
                }
            }
        }
        return false;
    }

    /** True if the player's body box overlaps any solid collision box. */
    public static boolean insideSolid(World w, double x, double y, double z, double height) {
        if (w == null) return false;
        AABB body = new AABB(x - 0.299, y + 0.02, z - 0.299,
                             x + 0.299, y + height - 0.02, z + 0.299);
        int minX = (int) Math.floor(body.minX), maxX = (int) Math.floor(body.maxX);
        int minY = (int) Math.floor(body.minY), maxY = (int) Math.floor(body.maxY);
        int minZ = (int) Math.floor(body.minZ), maxZ = (int) Math.floor(body.maxZ);
        for (int bx = minX; bx <= maxX; bx++) {
            for (int by = minY; by <= maxY; by++) {
                for (int bz = minZ; bz <= maxZ; bz++) {
                    Block b = w.getBlockAt(bx, by, bz);
                    if (!collidable(b)) continue;
                    if (body.intersects(b.getBoundingBox())) return true;
                }
            }
        }
        return false;
    }

    /** Vanilla slipperiness of the block the player is standing on. */
    public static double slipperiness(World w, double x, double y, double z) {
        if (w == null) return 0.6;
        Block b = w.getBlockAt((int) Math.floor(x),
                               (int) Math.floor(y - 0.5),
                               (int) Math.floor(z));
        return switch (b.getType()) {
            case ICE, PACKED_ICE, FROSTED_ICE -> 0.98;
            case BLUE_ICE -> 0.989;
            case SLIME_BLOCK -> 0.8;
            default -> 0.6;
        };
    }

    /** Cheap "is there ground within ~1 block under the feet" (step grace). */
    public static boolean nearGround(World w, double x, double y, double z, double height) {
        for (double d = 0.0; d <= 1.0; d += 0.25) {
            if (supported(w, x, y - d, z, height)) return true;
        }
        return false;
    }
}
