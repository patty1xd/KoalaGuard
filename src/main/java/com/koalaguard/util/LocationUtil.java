package com.koalaguard.util;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

public final class LocationUtil {

    private LocationUtil() {}

    /**
     * Server-authoritative ground check: scans the player's bounding-box
     * footprint just below their feet for a collidable block. Independent of
     * the (spoofable) client onGround flag.
     */
    public static boolean isOnGround(Player player) {
        Location loc = player.getLocation();
        double[] off = {-0.3, 0.0, 0.3};
        for (double ox : off) {
            for (double oz : off) {
                Block b = loc.clone().add(ox, -0.02, oz).getBlock();
                if (isSolid(b)) return true;
            }
        }
        return false;
    }

    public static boolean isSolid(Block b) {
        if (b == null) return false;
        Material m = b.getType();
        if (m.isAir()) return false;
        if (!m.isSolid()) return false;
        // passable solids
        String n = m.name();
        if (n.contains("SIGN") || n.contains("BANNER") || n.contains("TORCH")
                || n.contains("CARPET") || n.contains("BUTTON") || n.contains("SAPLING")
                || n.contains("RAIL")) return false;
        return true;
    }

    public static boolean inLiquid(Player player) {
        Location l = player.getLocation();
        Material feet = l.getBlock().getType();
        Material legs = l.clone().add(0, 0.5, 0).getBlock().getType();
        return isLiquid(feet) || isLiquid(legs) || player.isInWater();
    }

    public static boolean isLiquid(Material m) {
        return m == Material.WATER || m == Material.LAVA || m == Material.BUBBLE_COLUMN;
    }

    /** Eye location of the attacker to the centre of the victim's hitbox. */
    public static double combatDistance(Player attacker, Entity victim) {
        Location eye = attacker.getEyeLocation();
        double half = victim instanceof LivingEntity le ? le.getHeight() / 2.0 : 0.9;
        Location center = victim.getLocation().clone().add(0, half, 0);
        return eye.distance(center);
    }

    /** Smallest eye→hitbox distance accounting for the victim's AABB width. */
    public static double hitboxDistance(Player attacker, Entity victim) {
        Location eye = attacker.getEyeLocation();
        Location v = victim.getLocation();
        double w = victim.getWidth() / 2.0;
        double h = victim.getHeight();

        double dx = clampComponent(eye.getX(), v.getX() - w, v.getX() + w) - eye.getX();
        double dy = clampComponent(eye.getY(), v.getY(), v.getY() + h) - eye.getY();
        double dz = clampComponent(eye.getZ(), v.getZ() - w, v.getZ() + w) - eye.getZ();
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private static double clampComponent(double v, double min, double max) {
        return v < min ? min : Math.min(v, max);
    }

    /** Horizontal angle (deg) between the attacker's look and the victim. */
    public static double horizontalAngle(Player player, Entity target) {
        Vector look = player.getEyeLocation().getDirection();
        look.setY(0);
        if (look.lengthSquared() < 1.0E-7) return 0;
        look.normalize();
        Vector to = target.getLocation().toVector().subtract(player.getLocation().toVector());
        to.setY(0);
        if (to.lengthSquared() < 1.0E-7) return 0;
        to.normalize();
        double dot = Math.max(-1.0, Math.min(1.0, look.dot(to)));
        return Math.toDegrees(Math.acos(dot));
    }

    // ───────── packet-accurate variants (use the client's real yaw/pitch) ─────────

    public static Vector direction(float yaw, float pitch) {
        double y = Math.toRadians(yaw), p = Math.toRadians(pitch);
        double xz = Math.cos(p);
        return new Vector(-xz * Math.sin(y), -Math.sin(p), xz * Math.cos(y));
    }

    /** 3D angle (deg) between the client's real look vector and the line to the victim's hitbox centre. */
    public static double lookAngle(double ex, double ey, double ez, float yaw, float pitch, Entity victim) {
        Vector look = direction(yaw, pitch);
        Location v = victim.getLocation();
        Vector to = new Vector(v.getX() - ex, (v.getY() + victim.getHeight() / 2.0) - ey, v.getZ() - ez);
        if (to.lengthSquared() < 1.0E-7) return 0;
        to.normalize();
        double dot = Math.max(-1.0, Math.min(1.0, look.dot(to)));
        return Math.toDegrees(Math.acos(dot));
    }

    /** Eye(packet) → nearest point of the victim AABB. */
    public static double reachDistance(double ex, double ey, double ez, Entity victim) {
        Location v = victim.getLocation();
        double w = victim.getWidth() / 2.0;
        double h = victim.getHeight();
        double dx = clampComponent(ex, v.getX() - w, v.getX() + w) - ex;
        double dy = clampComponent(ey, v.getY(), v.getY() + h) - ey;
        double dz = clampComponent(ez, v.getZ() - w, v.getZ() + w) - ez;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }
}
