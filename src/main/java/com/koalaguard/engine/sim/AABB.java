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
}
