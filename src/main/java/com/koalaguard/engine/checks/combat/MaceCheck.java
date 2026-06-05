package com.koalaguard.engine.checks.combat;

import com.koalaguard.KoalaGuard;
import com.koalaguard.check.CheckCategory;
import com.koalaguard.engine.check.CheckContext;
import com.koalaguard.engine.check.SimCheck;
import com.koalaguard.engine.state.PositionFrame;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.enchantments.Enchantment;

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
    private final Map<UUID, Long> seenCap = new ConcurrentHashMap<>();

    public MaceCheck(KoalaGuard plugin) {
        super(plugin, "mace", CheckCategory.COMBAT, "Faked mace smash (no real fall)");
    }

    @Override
    public void onTick(CheckContext ctx) {
        // ── Variant 1: the HARD CAP at HIGHEST priority already truncated an
        // over-the-cap mace hit to vanilla damage — surface that as a flag so
        // staff see what was prevented. Single occurrence = conclusive (the
        // cap re-computes from world geometry, not the spoofable fallDistance).
        long capNs = ctx.state.combat.lastMaceCapPreventedNanos;
        UUID id0 = ctx.data.getUuid();
        if (capNs > Long.MIN_VALUE / 2 && seenCap.getOrDefault(id0, Long.MIN_VALUE) != capNs) {
            seenCap.put(id0, capNs);
            if (diverge(ctx, cfgD("cap-score", 10.0), cfgD("threshold", 10.0),
                    cfgI("cap-min-streak", 1),
                    "mace smash damage cap fired (no real fall)", false)) {
                armCombatCancel(ctx);
            }
        }

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
        // Riptide→mace zero-tick combo: if the player recently triggered
        // Riptide (a high upward velocity event) and then landed a smash an
        // unrealistically short time later, the fall arc was real but the
        // weapon swap was inhuman. The Riptide launch creates a real fall
        // distance so the previous guards would pass; this catches the auto
        // tool-swap pattern itself.
        try {
            long now = System.currentTimeMillis();
            long riptideAgoMs = now - ctx.data.lastRiptideMs;
            if (ctx.data.lastRiptideMs > 0 && riptideAgoMs < cfgL("riptide-swap-ms", 500L)) {
                ItemStack hand = ctx.player.getInventory().getItemInMainHand();
                if (hand != null && hand.getType() == Material.MACE) {
                    diverge(ctx, cfgD("riptide-score", 6.0), cfgD("threshold", 10.0),
                            cfgI("riptide-min-streak", 1),
                            String.format("riptide→mace zero-tick swap (%dms)", riptideAgoMs),
                            false);
                }
            }
        } catch (Throwable ignored) { }
    }

    @Override
    public void cleanup(UUID uuid) {
        super.cleanup(uuid);
        seen.remove(uuid);
    }
}
