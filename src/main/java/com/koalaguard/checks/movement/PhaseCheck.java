package com.koalaguard.checks.movement;

import com.koalaguard.KoalaGuard;
import com.koalaguard.check.MovementCheck;
import com.koalaguard.data.PlayerData;
import org.bukkit.Material;
import org.bukkit.entity.Player;

/**
 * Phase / NoClip detection.
 *
 * Both the feet and head block the player occupies are full solid blocks
 * they could not have legitimately entered. Requires a streak so a one-tick
 * server desync on a block boundary never flags.
 */
public final class PhaseCheck extends MovementCheck {

    public PhaseCheck(KoalaGuard plugin) {
        super(plugin, "phase", "Clipping through / standing inside solid blocks");
    }

    @Override
    public void handle(PlayerData data, Player player) {
        if (data.exemptFlying || data.exemptVehicle || data.exemptRiptide) {
            data.setInt(k("s"), 0);
            return;
        }
        long now = System.currentTimeMillis();
        if (now - data.lastTeleportMs < 1500 || now - data.lastVelocityMs < 1000) {
            data.setInt(k("s"), 0);
            return;
        }

        Material feet = player.getLocation().clone().add(0, 0.2, 0).getBlock().getType();
        Material head = player.getLocation().clone().add(0, 1.5, 0).getBlock().getType();

        if (isFullSolid(feet) && isFullSolid(head)) {
            int s = data.incInt(k("s"));
            if (s >= 4) {
                double buf = data.addBuffer(k("b"), 3.0, 9.0);
                if (buf >= 5.0) {
                    fail(data, player, "inside=" + feet + " streak=" + s);
                    data.setBuffer(k("b"), 1.0);
                }
                data.setInt(k("s"), 0);
            }
        } else {
            data.setInt(k("s"), 0);
        }
    }

    private boolean isFullSolid(Material m) {
        if (m.isAir() || !m.isSolid()) return false;
        String n = m.name();
        if (n.contains("SLAB") || n.contains("STAIR") || n.contains("WALL")
                || n.contains("FENCE") || n.contains("GATE") || n.contains("DOOR")
                || n.contains("TRAPDOOR") || n.contains("PANE") || n.contains("BARS")
                || n.contains("CHAIN") || n.contains("CARPET") || n.contains("SNOW")
                || n.contains("CHEST") || n.contains("BANNER") || n.contains("SIGN")
                || n.contains("HEAD") || n.contains("SKULL") || n.contains("CANDLE")
                || n.contains("BED") || n.contains("PISTON") || n.contains("SHULKER")
                || n.contains("CAULDRON") || n.contains("HOPPER") || n.contains("ANVIL")
                || n.contains("LANTERN") || n.contains("CAMPFIRE") || n.contains("SCAFFOLDING")
                || n.contains("LECTERN") || n.contains("BELL") || n.contains("GRINDSTONE")
                || n.contains("STONECUTTER") || n.contains("ENCHANTING")) return false;
        return true;
    }
}
