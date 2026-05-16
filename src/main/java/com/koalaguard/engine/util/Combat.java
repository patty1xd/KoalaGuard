package com.koalaguard.engine.util;

import com.koalaguard.engine.state.PositionFrame;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;

/**
 * Reconstruction helpers: turn a stored {@link PositionFrame} back into the
 * eye/look the client actually had on the attack tick, and resolve the victim
 * the interaction packet referenced. Everything here is geometry — no timing.
 */
public final class Combat {

    private Combat() {}

    public static double eyeHeight(Player p) {
        return p.isSneaking() ? 1.27 : 1.62;
    }

    public static Vector lookVector(float yaw, float pitch) {
        double y = Math.toRadians(yaw), pp = Math.toRadians(pitch);
        double xz = Math.cos(pp);
        return new Vector(-xz * Math.sin(y), -Math.sin(pp), xz * Math.cos(y));
    }

    /** Resolve the entity an INTERACT_ENTITY packet referenced, by id. */
    public static Entity resolveById(Player attacker, int entityId, double radius) {
        if (entityId < 0) return null;
        for (Entity e : attacker.getWorld().getNearbyEntities(
                attacker.getLocation(), radius, radius, radius)) {
            if (e.getEntityId() == entityId) return e;
        }
        return null;
    }

    /** Smallest distance from a reconstructed eye point to an entity AABB. */
    public static double distanceToBox(double ex, double ey, double ez, Entity victim) {
        BoundingBox b = victim.getBoundingBox();
        double dx = Math.max(Math.max(b.getMinX() - ex, 0), ex - b.getMaxX());
        double dy = Math.max(Math.max(b.getMinY() - ey, 0), ey - b.getMaxY());
        double dz = Math.max(Math.max(b.getMinZ() - ez, 0), ez - b.getMaxZ());
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /** Angle (deg) between the reconstructed look ray and the eye→box centre. */
    public static double aimAngle(double ex, double ey, double ez,
                                  float yaw, float pitch, Entity victim) {
        Vector look = lookVector(yaw, pitch);
        BoundingBox b = victim.getBoundingBox();
        Vector to = new Vector(b.getCenterX() - ex, b.getCenterY() - ey, b.getCenterZ() - ez);
        if (to.lengthSquared() < 1e-7) return 0;
        to.normalize();
        double dot = Math.max(-1.0, Math.min(1.0, look.dot(to)));
        return Math.toDegrees(Math.acos(dot));
    }

    /** Reconstructed eye position for a stored movement frame. */
    public static double[] eyeOf(PositionFrame f, double eyeHeight) {
        return new double[]{ f.x, f.y + eyeHeight, f.z };
    }
}
