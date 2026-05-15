package com.koalaguard.checks.player;

import com.koalaguard.KoalaGuard;
import com.koalaguard.checks.Check;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.BlockPlaceEvent;

import java.util.EnumSet;
import java.util.Set;

/**
 * Detects Meteor AirPlace.
 *
 * From Meteor's source (AirPlace.java):
 *   - Fires when mc.hitResult.getType() == HitResult.Type.MISS.
 *   - The client synthesises a fake block placement packet targeting
 *     whatever position the crosshair points at even though no block face
 *     was actually clicked.
 *   - Server sees a BlockPlaceEvent where the block-against (the face that
 *     the new block is placed on) is AIR or another non-solid material.
 *
 * Detection:
 *   BlockPlaceEvent where event.getBlockAgainst().getType() is non-solid
 *   AND the block-against is not simply the block below (legitimate
 *   "place on top of" placement) — we check all 6 faces of the newly
 *   placed block; if none is solid the placement is against air.
 *
 * Legitimate edge-cases excluded:
 *   - Placing on top of non-solid but interactive blocks (slabs, stairs,
 *     trapdoors, etc.): these ARE solid from Bukkit's isSolid() perspective.
 *   - Water / lava source blocks: excluded explicitly since waterlogging
 *     placements register the liquid as the against-block.
 */
public class AirPlaceCheck extends Check {

    private static final Set<Material> LIQUID_MATERIALS = EnumSet.of(
            Material.WATER, Material.LAVA
    );

    public AirPlaceCheck(KoalaGuard plugin) { super(plugin, "airplace"); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        if (!isEnabled()) return;
        Player player = event.getPlayer();
        if (isExempt(player)) return;
        if (plugin.shouldSuppressFlags(player)) return;

        Block against = event.getBlockAgainst();
        Material againstType = against.getType();

        // Liquids can legitimately be the "against" block for waterlogged placements
        if (LIQUID_MATERIALS.contains(againstType)) return;

        // If the against-block is solid the placement is legitimate
        if (againstType.isSolid()) return;

        // Additional guard: check that at least one neighbour of the placed block
        // is solid — prevents false positives from edge-case server-side resolution.
        Block placed = event.getBlockPlaced();
        boolean hasAdjacentSolid = false;
        for (BlockFace face : BlockFace.values()) {
            if (face == BlockFace.SELF) continue;
            Block neighbour = placed.getRelative(face);
            if (neighbour.getType().isSolid()) {
                hasAdjacentSolid = true;
                break;
            }
        }

        // No solid neighbour at all = completely floating — impossible legitimately
        if (!hasAdjacentSolid) {
            flag(player, "placed=" + placed.getType() + " against=" + againstType
                    + " loc=" + formatLoc(placed));
        }
    }

    private static String formatLoc(Block b) {
        return b.getWorld().getName() + "," + b.getX() + "," + b.getY() + "," + b.getZ();
    }
}
