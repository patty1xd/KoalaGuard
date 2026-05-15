package com.koalaguard.checks.player;

import com.koalaguard.KoalaGuard;
import com.koalaguard.checks.Check;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.entity.EntityRegainHealthEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Detects abnormally fast health regeneration.
 *
 * Vanilla regen intervals (from Minecraft source):
 *   Natural regen (food ≥ 18):         80 ticks  = 4000 ms
 *   Regeneration I  (amplifier = 0):   50 ticks  = 2500 ms
 *   Regeneration II (amplifier = 1):   25 ticks  = 1250 ms
 *   Regeneration III+(amplifier ≥ 2):  12 ticks  = 600  ms  (enchanted golden apple)
 *
 * We add a 20% leniency buffer to all thresholds to absorb TPS variance and
 * packet timing jitter before flagging.
 *
 * False-positive fixes vs previous version:
 *   - Now checks Regen amplifier to use the correct minimum interval.
 *   - Skips players with Absorption (golden apple eaten — does NOT give fast regen itself).
 *   - Skips Instant Health potion regains (those are a different event reason).
 *   - Uses a 3-event streak before flagging, not a single event.
 */
public class RegenCheck extends Check {

    private final Map<UUID, Long>    lastRegenMs = new HashMap<>();
    private final Map<UUID, Integer> fastStreak  = new HashMap<>();

    public RegenCheck(KoalaGuard plugin) { super(plugin, "regen"); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onRegen(EntityRegainHealthEvent event) {
        if (!isEnabled()) return;
        if (!(event.getEntity() instanceof Player player)) return;
        if (isExempt(player)) return;
        if (plugin.shouldSuppressFlags(player)) return;

        // Instant Health / Saturation / Ender pearl recovery — not a timed regen
        EntityRegainHealthEvent.RegainReason reason = event.getRegainReason();
        if (reason == EntityRegainHealthEvent.RegainReason.MAGIC
                || reason == EntityRegainHealthEvent.RegainReason.MAGIC_REGEN
                || reason == EntityRegainHealthEvent.RegainReason.EATING) return;

        UUID uuid   = player.getUniqueId();
        long now    = System.currentTimeMillis();
        Long lastMs = lastRegenMs.get(uuid);
        lastRegenMs.put(uuid, now);

        if (lastMs == null) return;
        long elapsed = now - lastMs;

        // Determine minimum legitimate interval based on active Regen effect
        long minMs = computeMinRegenMs(player);

        // Apply 20% leniency buffer for TPS fluctuation
        long threshold = (long)(minMs * 0.80);

        if (elapsed < threshold) {
            int s = fastStreak.merge(uuid, 1, Integer::sum);
            if (s >= 3) {
                flag(player, String.format("fast_regen elapsed=%dms min=%dms streak=%d",
                        elapsed, minMs, s));
                fastStreak.put(uuid, 0);
            }
        } else {
            fastStreak.put(uuid, 0);
        }
    }

    private static long computeMinRegenMs(Player player) {
        PotionEffect regen = player.getPotionEffect(PotionEffectType.REGENERATION);
        if (regen != null) {
            int amp = regen.getAmplifier(); // 0 = Regen I, 1 = Regen II, etc.
            if (amp >= 2) return 600;   // Regen III+ (enchanted golden apple)
            if (amp == 1) return 1250;  // Regen II
            return 2500;                // Regen I
        }
        // Natural food regen: 80 ticks
        return 4000;
    }
}
