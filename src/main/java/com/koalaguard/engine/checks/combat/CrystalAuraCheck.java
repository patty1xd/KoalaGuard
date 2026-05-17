package com.koalaguard.engine.checks.combat;

import com.koalaguard.KoalaGuard;
import com.koalaguard.check.CheckCategory;
import com.koalaguard.engine.check.CheckContext;
import com.koalaguard.engine.check.SimCheck;
import com.koalaguard.engine.packet.CapturedPacket;
import com.koalaguard.engine.packet.PacketKind;
import com.koalaguard.engine.util.Combat;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;

import java.util.List;

/**
 * CrystalAura / AnchorAura. Counts end-crystal attack packets per second. A
 * human caps out far below an aura's machine rate, so a sustained high crystal
 * break rate is the tell. (Place/aim is covered by MultiPlace / HitValidation.)
 */
public final class CrystalAuraCheck extends SimCheck {

    public CrystalAuraCheck(KoalaGuard plugin) {
        super(plugin, "crystalaura", CheckCategory.COMBAT, "Inhuman end-crystal break rate");
    }

    @Override
    public void onTick(CheckContext ctx) {
        if (ctx.unstableBasic()) return;
        long windowNs = 1_000_000_000L;
        long newest = -1;
        List<CapturedPacket> recent = ctx.state.log.recent(128);
        for (CapturedPacket p : recent) {
            if (p.kind == PacketKind.INTERACT_ENTITY
                    && String.valueOf(p.objA).contains("ATTACK")) { newest = p.recvNanos; break; }
        }
        if (newest < 0) { clean(ctx, 0.3); return; }

        int crystals = 0;
        for (CapturedPacket p : recent) {
            if (p.kind != PacketKind.INTERACT_ENTITY) continue;
            if (!String.valueOf(p.objA).contains("ATTACK")) continue;
            if (newest - p.recvNanos > windowNs) continue;
            Entity e = Combat.resolveById(ctx.player, p.intA, 8.0);
            if (e != null && e.getType() == EntityType.END_CRYSTAL) crystals++;
        }

        int max = cfgI("max-per-sec", 7);
        if (crystals >= max) {
            diverge(ctx, (crystals - max + 1) * cfgD("score-scale", 4.0),
                    cfgD("threshold", 9.0), cfgI("min-streak", 3),
                    crystals + " crystal breaks/s", false);
        } else {
            clean(ctx, 1.0);
        }
    }
}
