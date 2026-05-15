package com.koalaguard.checks.movement;

import com.koalaguard.KoalaGuard;
import com.koalaguard.checks.Check;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.PlayerMoveEvent;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Detects Meteor Phase.
 *
 * From Meteor's source (Phase.java):
 *   - Sends movement packets that place the player inside solid blocks.
 *   - The server accepts them because anti-cheat is server-side and the
 *     client reports a position that bypasses collision.
 *   - Observable: both the feet AND head block positions are solid blocks
 *     that a player should never be able to enter through normal play.
 *
 * Detection:
 *   Player's AABB occupies a solid, non-passable block at both feet and head.
 *   Streak of 3 consecutive ticks inside such blocks before flagging.
 *
 * Passable blocks excluded from detection (have solid getType() but passable hitbox):
 *   Chests, barrels, crafting tables, furnaces, hoppers, beehives, composters,
 *   fences (as they're passable at player-height), and stairs/slabs.
 */
public class PhaseCheck extends Check {

    // Blocks that report isSolid() but have open or passable hitboxes
    private static final Set<Material> PASSABLE_SOLIDS = EnumSet.of(
            Material.CHEST, Material.TRAPPED_CHEST, Material.ENDER_CHEST,
            Material.BARREL, Material.CRAFTING_TABLE, Material.FURNACE,
            Material.BLAST_FURNACE, Material.SMOKER, Material.HOPPER,
            Material.COMPOSTER, Material.BEEHIVE, Material.BEE_NEST,
            Material.LECTERN, Material.ENCHANTING_TABLE,
            Material.OAK_FENCE, Material.SPRUCE_FENCE, Material.BIRCH_FENCE,
            Material.JUNGLE_FENCE, Material.ACACIA_FENCE, Material.DARK_OAK_FENCE,
            Material.NETHER_BRICK_FENCE, Material.BAMBOO_FENCE,
            Material.CHERRY_FENCE, Material.MANGROVE_FENCE,
            Material.OAK_FENCE_GATE, Material.SPRUCE_FENCE_GATE,
            Material.BIRCH_FENCE_GATE, Material.JUNGLE_FENCE_GATE,
            Material.ACACIA_FENCE_GATE, Material.DARK_OAK_FENCE_GATE,
            Material.NETHER_BRICK_FENCE, Material.IRON_BARS, Material.GLASS_PANE,
            Material.WHITE_STAINED_GLASS_PANE, Material.ORANGE_STAINED_GLASS_PANE,
            Material.MAGENTA_STAINED_GLASS_PANE, Material.LIGHT_BLUE_STAINED_GLASS_PANE
    );

    private final Map<UUID, Integer> insideStreak = new HashMap<>();

    public PhaseCheck(KoalaGuard plugin) { super(plugin, "phase"); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (!isEnabled()) return;
        Player player = event.getPlayer();
        if (isExempt(player)) return;
        if (plugin.shouldSuppressFlags(player)) return;

        if (player.isFlying() || player.getAllowFlight()) return;
        if (player.getGameMode() == GameMode.CREATIVE || player.getGameMode() == GameMode.SPECTATOR) return;
        if (player.isInsideVehicle()) return;

        Location to = event.getTo();
        if (to == null) return;

        Material feet = to.getBlock().getType();
        Material head = to.getBlock().getRelative(0, 1, 0).getType();

        UUID uuid = player.getUniqueId();

        if (isPhaseBlock(feet) && isPhaseBlock(head)) {
            int streak = insideStreak.merge(uuid, 1, Integer::sum);
            if (streak >= 3) {
                flag(player, "inside=" + feet.name() + " streak=" + streak);
                insideStreak.put(uuid, 0);
            }
        } else {
            insideStreak.put(uuid, 0);
        }
    }

    private boolean isPhaseBlock(Material mat) {
        if (!mat.isSolid()) return false;
        if (PASSABLE_SOLIDS.contains(mat)) return false;
        // Stairs and slabs: names contain these keywords
        String name = mat.name();
        if (name.contains("SLAB") || name.contains("STAIR") || name.contains("WALL")) return false;
        // Trapdoors, doors, gates
        if (name.contains("TRAPDOOR") || name.contains("DOOR") || name.contains("GATE")) return false;
        // Buttons, pressure plates
        if (name.contains("BUTTON") || name.contains("PLATE")) return false;
        return true;
    }
}
