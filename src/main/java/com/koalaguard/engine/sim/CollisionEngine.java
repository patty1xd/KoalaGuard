package com.koalaguard.engine.sim;

import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.VoxelShape;

import java.util.ArrayList;
import java.util.List;

/**
 * Server-authoritative world collision (MAIN THREAD ONLY).
 *
 * Uses the PRECISE per-block {@link Block#getCollisionShape()} sub-boxes, not
 * the coarse {@link Block#getBoundingBox()} outer box. That distinction is the
 * Phase false-positive fix: a stair's outer box is a full 1×1×1 cube, so a
 * player standing on the lower step "intersects" it; the real collision shape
 * is two partial boxes the player legitimately stands on top of, never inside.
 */
public final class CollisionEngine {

    private CollisionEngine() {}

    public static boolean collidable(Block b) {
        if (b == null) return false;
        Material m = b.getType();
        if (m.isAir() || b.isLiquid()) return false;
        return !b.isPassable();
    }

    /** World-space collision boxes for a block (precise; empty if passable). */
    private static List<BoundingBox> worldBoxes(World w, int bx, int by, int bz) {
        Block b = w.getBlockAt(bx, by, bz);
        if (!collidable(b)) return List.of();
        List<BoundingBox> out = new ArrayList<>(4);
        try {
            VoxelShape shape = b.getCollisionShape();
            for (BoundingBox bb : shape.getBoundingBoxes()) {
                out.add(bb.clone().shift(bx, by, bz));   // shape is block-relative
            }
        } catch (Throwable ignored) { }
        if (out.isEmpty()) {
            BoundingBox bb = b.getBoundingBox();          // robust fallback
            if (bb != null && bb.getVolume() > 0) out.add(bb);
        }
        return out;
    }

    /** A collidable surface within ~0.5 below the feet (precise top face). */
    public static boolean supported(World w, double x, double y, double z, double height) {
        if (w == null) return true;
        int minX = (int) Math.floor(x - 0.3), maxX = (int) Math.floor(x + 0.3);
        int minZ = (int) Math.floor(z - 0.3), maxZ = (int) Math.floor(z + 0.3);
        for (int by = (int) Math.floor(y - 0.55); by <= (int) Math.floor(y + 0.02); by++) {
            for (int bx = minX; bx <= maxX; bx++) {
                for (int bz = minZ; bz <= maxZ; bz++) {
                    for (BoundingBox box : worldBoxes(w, bx, by, bz)) {
                        if (box.getMaxY() >= y - 0.501 && box.getMaxY() <= y + 0.02
                                && box.getMaxX() > x - 0.3 && box.getMinX() < x + 0.3
                                && box.getMaxZ() > z - 0.3 && box.getMinZ() < z + 0.3) {
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }

    /** True only if the player BODY is genuinely embedded in solid geometry. */
    public static boolean insideSolid(World w, double x, double y, double z, double height) {
        if (w == null) return false;
        // Inset hard so merely brushing a wall / standing on a step is not
        // "inside"; Phase additionally requires this for several ticks.
        BoundingBox body = new BoundingBox(
                x - 0.25, y + 0.10, z - 0.25,
                x + 0.25, y + height - 0.10, z + 0.25);
        int minX = (int) Math.floor(body.getMinX()), maxX = (int) Math.floor(body.getMaxX());
        int minY = (int) Math.floor(body.getMinY()), maxY = (int) Math.floor(body.getMaxY());
        int minZ = (int) Math.floor(body.getMinZ()), maxZ = (int) Math.floor(body.getMaxZ());
        for (int bx = minX; bx <= maxX; bx++) {
            for (int by = minY; by <= maxY; by++) {
                for (int bz = minZ; bz <= maxZ; bz++) {
                    for (BoundingBox box : worldBoxes(w, bx, by, bz)) {
                        if (box.overlaps(body)) return true;
                    }
                }
            }
        }
        return false;
    }

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

    /** A solid block pressed against the player's side (Spider needs a wall). */
    public static boolean touchingWall(World w, double x, double y, double z) {
        if (w == null) return false;
        int by0 = (int) Math.floor(y + 0.2), by1 = (int) Math.floor(y + 1.4);
        double[][] dirs = {{0.35, 0}, {-0.35, 0}, {0, 0.35}, {0, -0.35}};
        for (double[] d : dirs) {
            int bx = (int) Math.floor(x + d[0]);
            int bz = (int) Math.floor(z + d[1]);
            for (int by = by0; by <= by1; by++) {
                if (collidable(w.getBlockAt(bx, by, bz))) return true;
            }
        }
        return false;
    }

    /** Ground within ~1 block under the feet (step / coyote grace). */
    public static boolean nearGround(World w, double x, double y, double z, double height) {
        for (double d = 0.0; d <= 1.0; d += 0.25) {
            if (supported(w, x, y - d, z, height)) return true;
        }
        return false;
    }
}
