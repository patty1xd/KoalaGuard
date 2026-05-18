package com.koalaguard.engine.checks.combat;

import com.koalaguard.KoalaGuard;
import com.koalaguard.check.CheckCategory;
import com.koalaguard.engine.check.CheckContext;
import com.koalaguard.engine.check.SimCheck;
import com.koalaguard.engine.state.PositionFrame;
import org.bukkit.Material;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Mace smash exploit ("MaceKill" / "MaceDamage"). The 1.21 mace deals a huge
 * bonus ONLY when the attacker genuinely FELL — the server scales the smash by
 * the attacker's real fall distance. A grounded mace hit deals normal melee
 * damage. The cheat makes the server hand out (or directly injects) the smash
 * bonus while the attacker is standing on the ground — "100 hearts while bro
 * is on the ground".
 *
 * Detection is server-authoritative and damage-driven:
 *   • the SERVER-computed damage of the mace hit (incl. any smash bonus) is
 *     captured from EntityDamageByEntityEvent,
 *   • a hit whose damage is far above any non-smash mace hit is a SMASH,
 *   • a real smash REQUIRES a genuine fall. We confirm "no real fall" two
 *     independent ways: the server's own fall view at the hit instant, AND the
 *     reconstructed frames (geometry-true simGround + a real descent arc). A
 *     legit jump/air smash has a real fall on BOTH → never flags. A grounded
 *     normal mace hit never reaches smash damage → never flags. Only a
 *     smash-magnitude hit with no real fall confirms.
 *
 * No {@code claimedAir} requirement (the old check's fatal bug — a cheat that
 * sends ordinary grounded movement never claims air, so that check could
 * never fire). No frame dependency for the trigger (works while standing
 * perfectly still — the classic test).
 */
public final class MaceCheck extends SimCheck {

    private final Map<UUID, Long> seen = new ConcurrentHashMap<>();

    public MaceCheck(KoalaGuard plugin) {
        super(plugin, "mace", CheckCategory.COMBAT, "Faked mace smash (no real fall)");
    }

    @Override
    public void onTick(CheckContext ctx) {
        long hitNs = ctx.state.combat.lastMaceHitNanos;
        if (hitNs <= Long.MIN_VALUE / 2) return;             // no mace hit yet
        UUID id = ctx.data.getUuid();
        if (seen.getOrDefault(id, Long.MIN_VALUE) == hitNs) return;
        seen.put(id, hitNs);
        if (ctx.unstableBasic()) return;

        double dmg = ctx.state.combat.lastMaceDamage;
        double vanillaMax = cfgD("vanilla-max-damage", 25.0);
        if (dmg <= vanillaMax) { clean(ctx, 2.0); return; }   // normal mace hit

        // ── A SMASH-magnitude hit landed. Did a genuine fall actually occur? ──

        // (1) Server's own view at the hit instant. Real fall ⇒ legit.
        float serverFall = ctx.state.combat.lastMaceFallDistance;
        boolean serverSawFall = serverFall >= (float) cfgD("min-fall-blocks", 1.0);

        // (2) Independent geometric reconstruction over the frames around the
        //     hit. A genuine fall genuinely loses top-face support (simGround
        //     false) AND has a real downward arc — a packet-spoofed "fall"
        //     keeps real ground support the whole time.
        long atk = ctx.state.combat.lastAttackTick;
        int win = cfgI("window-ticks", 8);
        double maxDown = 0;
        boolean leftGround = false, haveFrames = false;
        for (PositionFrame f : ctx.state.recentFrames(20)) {
            if (atk >= 0 && (f.tick > atk || f.tick < atk - win)) continue;
            haveFrames = true;
            maxDown = Math.min(maxDown, f.dy);
            if (!f.simGround) leftGround = true;
        }
        boolean realFall = leftGround && maxDown < -cfgD("min-fall-dy", 0.25);

        boolean genuineFall = serverSawFall || realFall;
        if (genuineFall) { clean(ctx, 2.0); return; }         // legit jump/air smash

        // Smash damage with no real fall by EITHER measure ⇒ forged smash.
        if (diverge(ctx, cfgD("score", 8.0), cfgD("threshold", 10.0),
                cfgI("min-streak", 2),
                String.format(
                    "mace smash %.1f dmg with no real fall (serverFall=%.2f, simGround=%b, maxDown=%.3f, frames=%b)",
                    dmg, serverFall, !leftGround, maxDown, haveFrames),
                false)) {
            armCombatCancel(ctx);
        }
    }

    @Override
    public void cleanup(UUID uuid) {
        super.cleanup(uuid);
        seen.remove(uuid);
    }
}
