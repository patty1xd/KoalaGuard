package com.koalaguard.engine.sim;

import org.bukkit.util.BoundingBox;

/** Lightweight axis-aligned box used by the collision engine. */
public final class AABB {

    public double minX, minY, minZ, maxX, maxY, maxZ;

    public AABB(double minX, double minY, double minZ,
                double maxX, double maxY, double maxZ) {
        this.minX = minX; this.minY = minY; this.minZ = minZ;
        this.maxX = maxX; this.maxY = maxY; this.maxZ = maxZ;
    }

    /** Player box: width 0.6, height 1.8 (0.6 sneaking handled by caller). */
    public static AABB player(double x, double y, double z, double height) {
        return new AABB(x - 0.3, y, z - 0.3, x + 0.3, y + height, z + 0.3);
    }

    public AABB expand(double dx, double dy, double dz) {
        return new AABB(minX - dx, minY - dy, minZ - dz,
                        maxX + dx, maxY + dy, maxZ + dz);
    }

    public boolean intersects(BoundingBox b) {
        return maxX > b.getMinX() && minX < b.getMaxX()
            && maxY > b.getMinY() && minY < b.getMaxY()
            && maxZ > b.getMinZ() && minZ < b.getMaxZ();
    }

    public boolean intersects(AABB b) {
        return maxX > b.minX && minX < b.maxX
            && maxY > b.minY && minY < b.maxY
            && maxZ > b.minZ && minZ < b.maxZ;
    }

    /**
     * Slab-test sweep: does the segment from (x0,y0,z0) → (x1,y1,z1) pierce
     * this box? Returns true on any intersection or if the start point is
     * already inside. Used by combat checks for raycast hits and by
     * movement checks for "did I move through this block this tick".
     */
    public boolean sweep(double x0, double y0, double z0,
                         double x1, double y1, double z1) {
        double dx = x1 - x0, dy = y1 - y0, dz = z1 - z0;
        double tMin = 0.0, tMax = 1.0;
        // X
        if (Math.abs(dx) < 1e-9) {
            if (x0 < minX || x0 > maxX) return false;
        } else {
            double t1 = (minX - x0) / dx, t2 = (maxX - x0) / dx;
            if (t1 > t2) { double t = t1; t1 = t2; t2 = t; }
            if (t1 > tMin) tMin = t1;
            if (t2 < tMax) tMax = t2;
            if (tMin > tMax) return false;
        }
        // Y
        if (Math.abs(dy) < 1e-9) {
            if (y0 < minY || y0 > maxY) return false;
        } else {
            double t1 = (minY - y0) / dy, t2 = (maxY - y0) / dy;
            if (t1 > t2) { double t = t1; t1 = t2; t2 = t; }
            if (t1 > tMin) tMin = t1;
            if (t2 < tMax) tMax = t2;
            if (tMin > tMax) return false;
        }
        // Z
        if (Math.abs(dz) < 1e-9) {
            if (z0 < minZ || z0 > maxZ) return false;
        } else {
            double t1 = (minZ - z0) / dz, t2 = (maxZ - z0) / dz;
            if (t1 > t2) { double t = t1; t1 = t2; t2 = t; }
            if (t1 > tMin) tMin = t1;
            if (t2 < tMax) tMax = t2;
            if (tMin > tMax) return false;
        }
        return true;
    }
}
