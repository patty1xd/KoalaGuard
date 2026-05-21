package com.koalaguard.engine.checks.combat;

import com.koalaguard.KoalaGuard;
import com.koalaguard.check.CheckCategory;
import com.koalaguard.engine.check.CheckContext;
import com.koalaguard.engine.check.SimCheck;
import com.koalaguard.engine.packet.CapturedPacket;
import com.koalaguard.engine.packet.PacketKind;
import com.koalaguard.engine.util.Combat;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;

import java.util.List;

/**
 * CrystalAura / AnchorAura. Detects the two PvP cycle behaviours a human
 * cannot sustain:
 *
 *  S1  end-crystal breaks per second (the original signal — covers KAMI auto-
 *      place, CrystalCore, CRE Client, every classic crystal aura);
 *  S2  respawn-anchor cycle rate per second — counts BLOCK_PLACE packets on
 *      RESPAWN_ANCHOR or CHARGED_RESPAWN_ANCHOR positions plus right-click
 *      detonations. Vanilla can charge / detonate maybe 2-3/s by hand; an
 *      AnchorAura runs at 20+/s. Catches Wurst AnchorAura, CrystalCore
 *      anchor-mode, "safe anchor" auto-placement.
 *
 * Both share the per-second window. Either over threshold flags. Other aspects
 * (silent aim, multi-place, fast inventory swap) are caught by AimA-H,
 * MultiPlace, InventoryAction independently, so this check stays focused.
 */
public final class CrystalAuraCheck extends SimCheck {

    public CrystalAuraCheck(KoalaGuard plugin) {
        super(plugin, "crystalaura", CheckCategory.COMBAT,
                "Inhuman end-crystal / respawn-anchor cycle rate");
    }

    @Override
    public void onTick(CheckContext ctx) {
        if (ctx.unstableBasic()) return;
        long windowNs = 1_000_000_000L;
        long nowNs = System.nanoTime();
        List<CapturedPacket> recent = ctx.state.log.recent(160);

        int crystals = 0;
        int anchorOps = 0;
        World w = ctx.player.getWorld();
        for (CapturedPacket p : recent) {
            if (nowNs - p.recvNanos > windowNs) continue;
            if (p.kind == PacketKind.INTERACT_ENTITY
                    && String.valueOf(p.objA).contains("ATTACK")) {
                Entity e = Combat.resolveById(ctx.player, p.intA, 8.0);
                if (e != null && e.getType() == EntityType.END_CRYSTAL) crystals++;
            } else if (p.kind == PacketKind.BLOCK_PLACE && p.hasPos) {
                Material clicked = w.getBlockAt((int) p.x, (int) p.y, (int) p.z).getType();
                if (clicked == Material.RESPAWN_ANCHOR) anchorOps++;
            }
        }

        int maxCrystal = cfgI("max-per-sec", 7);
        int maxAnchor = cfgI("max-anchor-per-sec", 5);
        boolean badCrystal = crystals >= maxCrystal;
        boolean badAnchor = anchorOps >= maxAnchor;

        if (badCrystal || badAnchor) {
            double bad = Math.max(0, crystals - maxCrystal + 1) * cfgD("score-scale", 4.0)
                       + Math.max(0, anchorOps - maxAnchor + 1) * cfgD("anchor-score-scale", 4.0);
            diverge(ctx, bad, cfgD("threshold", 9.0), cfgI("min-streak", 3),
                    String.format("crystal/s=%d anchor-ops/s=%d", crystals, anchorOps), false);
        } else if (crystals > 0 || anchorOps > 0) {
            clean(ctx, 1.0);
        } else {
            clean(ctx, 0.3);
        }
    }
}
