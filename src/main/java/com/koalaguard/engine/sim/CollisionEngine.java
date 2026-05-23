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

    /**
     * Clip-dedicated path test. True if the straight segment the player moved
     * along passes through (or ends inside) a collidable block.
     *
     * Why this exists separately from {@link #rayBlocked}: rayBlocked SKIPS the
     * source and target block cells and samples at a coarse 0.2-block step at a
     * single height — perfect for "is there a wall between me and who I clicked"
     * but FATAL for clip detection. A 10–20 block .hclip/.vclip frequently has
     * its wall inside the skipped endpoint cells (the cheat clips you to valid
     * air just past a wall that is adjacent to your start), so rayBlocked read
     * "clear" and the clip slipped through entirely. This test skips NOTHING,
     * steps finely (0.1 block), and scans the whole player body height — a clip
     * of even one block through a thin wall is caught, while an open lane (a
     * legit fast move) still reads clear because no body-height sample is solid.
     */
    public static boolean solidOnSegment(World w,
                                         double x0, double y0, double z0,
                                         double x1, double y1, double z1) {
        if (w == null) return false;
        double dx = x1 - x0, dy = y1 - y0, dz = z1 - z0;
        double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (dist < 0.10) return false;
        int steps = (int) Math.ceil(dist / 0.10) + 1;
        // Continuous Y sweep across the full vanilla 1.8-tall hitbox. The
        // previous 3-fixed-height sample (0.10/0.90/1.60) missed any wall
        // whose Y span fell BETWEEN samples — e.g. a 1-block wall at y+0.5..
        // y+1.5 evaluated AIR/AIR/AIR and a vclip slipped through. Stepping
        // 0.45 blocks (just less than the smallest non-air vanilla block
        // height — a 0.5 slab) guarantees at least one body sample lands on
        // any solid voxel the player's bounding box would intersect.
        for (int i = 0; i <= steps; i++) {
            double t = (double) i / steps;
            double cx = x0 + dx * t, cy = y0 + dy * t, cz = z0 + dz * t;
            int bx = (int) Math.floor(cx);
            int bz = (int) Math.floor(cz);
            for (double hh = 0.0; hh <= 1.80; hh += 0.45) {
                int by = (int) Math.floor(cy + hh);
                if (collidable(w.getBlockAt(bx, by, bz))) return true;
            }
            // Always sample the very top of the body too (eye level), since
            // a half-step hh increment could otherwise skip past 1.80 exactly.
            int byTop = (int) Math.floor(cy + 1.80);
            if (collidable(w.getBlockAt(bx, byTop, bz))) return true;
        }
        return false;
    }

    /**
     * True if a full solid block obstructs the straight segment from the eye to
     * the target point. Stepped sampling (≈0.2 block) — coarse on purpose: it is
     * only used to catch combat / placement THROUGH a wall, never to grant a
     * bypass, so a false "clear" on a thin block is harmless and a false
     * "blocked" is impossible for an open line. The block containing the eye and
     * the block containing the target are skipped (you legitimately stand next
     * to / click the target block itself).
     */
    public static boolean rayBlocked(World w,
                                     double ex, double ey, double ez,
                                     double tx, double ty, double tz) {
        if (w == null) return false;
        double dx = tx - ex, dy = ty - ey, dz = tz - ez;
        double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (dist < 0.35) return false;
        int sbx = (int) Math.floor(ex), sby = (int) Math.floor(ey), sbz = (int) Math.floor(ez);
        int tbx = (int) Math.floor(tx), tby = (int) Math.floor(ty), tbz = (int) Math.floor(tz);
        int steps = (int) Math.ceil(dist / 0.2);
        for (int i = 1; i < steps; i++) {
            double t = (double) i / steps;
            int bx = (int) Math.floor(ex + dx * t);
            int by = (int) Math.floor(ey + dy * t);
            int bz = (int) Math.floor(ez + dz * t);
            if ((bx == sbx && by == sby && bz == sbz)
                    || (bx == tbx && by == tby && bz == tbz)) continue;
            Block b = w.getBlockAt(bx, by, bz);
            if (b == null) continue;
            Material m = b.getType();
            if (m.isAir() || b.isLiquid() || b.isPassable()) continue;
            return true;
        }
        return false;
    }
}
