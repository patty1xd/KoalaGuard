package com.koalaguard.engine.checks.world;

import com.koalaguard.KoalaGuard;
import com.koalaguard.check.CheckCategory;
import com.koalaguard.engine.check.CheckContext;
import com.koalaguard.engine.check.SimCheck;
import com.koalaguard.engine.packet.CapturedPacket;
import com.koalaguard.engine.packet.PacketKind;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffectType;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * FastBreak / InstaBreak. The cheat sends a {@code FINISHED_DIGGING} for a
 * block far sooner than the vanilla block-break formula allows. Vanilla:
 * <pre>
 *   damagePerTick = toolSpeed * (1 + 0.2 * hasteLevel) * (other modifiers)
 *   ticksToBreak  = ceil(hardness * 30 / damagePerTick)
 * </pre>
 * Anything materially under that is impossible. Survival cobblestone with
 * stone pick = 1.25s; netherite + Efficiency V + Haste II = ~0.10s; the cheat
 * tries to do 0 ticks regardless.
 *
 * Covers every known cheat-client variant — Wurst FastBreak, LiquidBounce
 * Speedmine, ForgeHax FastBreak, Meteor NoSlow-block, RusherHack InstaMine —
 * because they all share the same wire signature: a START_DIGGING followed
 * by a FINISHED_DIGGING for the same block at sub-vanilla latency.
 *
 * FP-safe by margin: a generous ×0.4 tolerance on the computed expected time
 * means transient lag spikes / Haste tier transitions never trip it; large
 * sample (consecutive blocks broken too fast) confirms.
 */
public final class FastBreakCheck extends SimCheck {

    private static final class Pending {
        long startNanos;
        int  bx, by, bz;
    }

    private final Map<UUID, Pending> pending = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastSeq = new ConcurrentHashMap<>();

    public FastBreakCheck(KoalaGuard plugin) {
        super(plugin, "fastbreak", CheckCategory.WORLD,
                "Block broken faster than vanilla allows");
    }

    @Override
    public void onTick(CheckContext ctx) {
        if (ctx.unstableBasic()) return;
        Player p = ctx.player;
        if (p.getGameMode() == GameMode.CREATIVE) return;     // instabreak is vanilla here

        UUID id = ctx.data.getUuid();
        long seen = lastSeq.getOrDefault(id, Long.MIN_VALUE);
        long max = seen;

        // Walk newest→oldest is fine for matching, but we need chronological
        // order for the START→FINISH pairing — so iterate oldest first.
        List<CapturedPacket> chrono = new java.util.ArrayList<>(ctx.state.log.recent(96));
        java.util.Collections.reverse(chrono);

        for (CapturedPacket pkt : chrono) {
            if (pkt.kind != PacketKind.DIGGING) continue;
            if (!pkt.hasPos) continue;
            if (pkt.seq <= seen) continue;
            if (pkt.seq > max) max = pkt.seq;

            if ("START_DIGGING".equals(pkt.strA)) {
                Pending pe = new Pending();
                pe.startNanos = pkt.recvNanos;
                pe.bx = (int) pkt.x; pe.by = (int) pkt.y; pe.bz = (int) pkt.z;
                pending.put(id, pe);
            } else if ("FINISHED_DIGGING".equals(pkt.strA)) {
                Pending pe = pending.remove(id);
                if (pe == null) continue;
                if (pe.bx != (int) pkt.x || pe.by != (int) pkt.y || pe.bz != (int) pkt.z) continue;
                long elapsedMs = (pkt.recvNanos - pe.startNanos) / 1_000_000L;
                if (elapsedMs <= 0) continue;

                Block b = p.getWorld().getBlockAt(pe.bx, pe.by, pe.bz);
                if (b == null) continue;
                Material m = b.getType();
                if (m.isAir() || m == Material.WATER || m == Material.LAVA) continue;
                float hardness = m.getHardness();
                if (hardness <= 0.0f) continue;                // bush, sapling, etc.
                if (hardness < cfgD("min-hardness", 0.4)) continue;

                double expectedMs = computeExpectedBreakMs(p, b, hardness);
                if (expectedMs <= 0) continue;

                double tolerance = cfgD("tolerance-fraction", 0.40);
                double floor = expectedMs * tolerance;
                if (elapsedMs < floor) {
                    diverge(ctx, (floor - elapsedMs) / Math.max(50.0, floor)
                                * cfgD("score-scale", 8.0),
                            cfgD("threshold", 9.0), cfgI("min-streak", 3),
                            String.format(
                                "broke %s in %dms (vanilla floor %.0fms, expected %.0fms)",
                                m, elapsedMs, floor, expectedMs), false);
                } else {
                    clean(ctx, 0.5);
                }
            }
        }
        lastSeq.put(id, max);
    }

    /**
     * Tool/enchant/effect-aware break time estimate. Mojang 1.21:
     *   speed = toolBaseSpeed (1.0 for hand, 5–12 for proper tool tiers)
     *   if Efficiency level > 0: speed += level² + 1
     *   if Haste:   speed *= 1 + 0.2 * hasteLevel
     *   if MiningFatigue: speed *= 0.3^fatigueLevel
     *   ticks = ceil(hardness * 30 / speed)
     */
    private double computeExpectedBreakMs(Player p, Block b, float hardness) {
        ItemStack tool = p.getInventory().getItemInMainHand();
        Material m = b.getType();
        double speed = baseSpeed(tool, m);
        boolean correctTool = isCorrectTool(tool, m);
        if (!correctTool) {
            // Wrong tool: the player can still break it slowly, hardness*5.
            // Vanilla: ticks = hardness * 30 / speed but with hardness*5 base
            // (uses the slower path).
            return Math.max(50.0, hardness * 5.0 * 1000.0 / Math.max(1.0, speed));
        }
        try {
            int eff = tool.getEnchantmentLevel(Enchantment.EFFICIENCY);
            if (eff > 0) speed += eff * eff + 1;
        } catch (Throwable ignored) { }
        try {
            var haste = p.getPotionEffect(PotionEffectType.HASTE);
            if (haste != null) speed *= 1.0 + 0.2 * (haste.getAmplifier() + 1);
        } catch (Throwable ignored) { }
        try {
            var fat = p.getPotionEffect(PotionEffectType.MINING_FATIGUE);
            if (fat != null) speed *= Math.pow(0.3, fat.getAmplifier() + 1);
        } catch (Throwable ignored) { }
        // Conduit Power grants effective Aqua Affinity (no underwater /5
        // penalty). Without this check, expected break time was 5x too high
        // for any conduit user and FastBreak silently missed real cheats.
        if (p.isInWater() && !hasAquaAffinity(p) && !hasConduitPower(p)) speed /= 5.0;
        if (!p.isOnGround()) speed /= 5.0;

        if (speed >= hardness * 30.0) return 50.0;            // legitimate insta-mine

        double ticks = Math.ceil(hardness * 30.0 / Math.max(0.01, speed));
        return ticks * 50.0;                                  // 50 ms per tick
    }

    private static boolean isCorrectTool(ItemStack tool, Material block) {
        if (tool == null) return false;
        String name = tool.getType().name();
        String b = block.name();
        // Pickaxes for ores / stones / metal blocks
        if (name.endsWith("_PICKAXE")) {
            return b.endsWith("_ORE") || b.contains("STONE") || b.contains("DIAMOND")
                    || b.contains("IRON") || b.contains("GOLD") || b.contains("COPPER")
                    || b.contains("ANCIENT_DEBRIS") || b.equals("OBSIDIAN")
                    || b.equals("ANDESITE") || b.equals("DIORITE") || b.equals("GRANITE")
                    || b.equals("DEEPSLATE") || b.contains("NETHERITE") || b.contains("BRICKS")
                    // 1.17+ deepslate / dripstone / amethyst / 1.20+ tuff / 1.21
                    || b.equals("TUFF") || b.equals("CALCITE") || b.equals("BASALT")
                    || b.equals("SMOOTH_BASALT") || b.equals("BLACKSTONE")
                    || b.contains("DRIPSTONE") || b.contains("AMETHYST")
                    || b.contains("ANVIL") || b.contains("CAULDRON")
                    || b.equals("END_STONE") || b.equals("PURPUR_BLOCK")
                    || b.contains("QUARTZ") || b.equals("REDSTONE_LAMP")
                    || b.equals("ICE") || b.equals("PACKED_ICE") || b.equals("BLUE_ICE")
                    || b.equals("CONCRETE") || b.endsWith("_CONCRETE")
                    || b.equals("TERRACOTTA") || b.endsWith("_TERRACOTTA");
        }
        if (name.endsWith("_AXE")) {
            return b.endsWith("_LOG") || b.endsWith("_WOOD") || b.endsWith("_PLANKS")
                    || b.contains("BOOKSHELF") || b.contains("CHEST")
                    // 1.21 axe-mineable
                    || b.equals("MELON") || b.equals("PUMPKIN") || b.equals("CARVED_PUMPKIN")
                    || b.equals("JACK_O_LANTERN") || b.equals("BAMBOO_BLOCK")
                    || b.equals("STRIPPED_BAMBOO_BLOCK") || b.contains("MUSHROOM_BLOCK")
                    || b.equals("MUSHROOM_STEM") || b.endsWith("_DOOR") || b.endsWith("_FENCE")
                    || b.endsWith("_FENCE_GATE") || b.endsWith("_SIGN")
                    || b.endsWith("_TRAPDOOR") || b.endsWith("_STAIRS")
                    || b.endsWith("_SLAB") || b.endsWith("_PRESSURE_PLATE")
                    || b.endsWith("_BUTTON");
        }
        if (name.endsWith("_SHOVEL")) {
            return b.equals("DIRT") || b.equals("GRASS_BLOCK") || b.contains("SAND")
                    || b.contains("GRAVEL") || b.equals("SNOW") || b.equals("SNOW_BLOCK")
                    || b.equals("CLAY") || b.equals("MUD") || b.equals("PODZOL")
                    || b.equals("MYCELIUM") || b.equals("SOUL_SAND") || b.equals("SOUL_SOIL")
                    || b.equals("DIRT_PATH") || b.equals("ROOTED_DIRT")
                    || b.equals("COARSE_DIRT") || b.equals("FARMLAND")
                    || b.equals("RED_SAND") || b.equals("MUDDY_MANGROVE_ROOTS")
                    || b.endsWith("POWDER_SNOW");
        }
        if (name.endsWith("_HOE")) {
            return b.contains("LEAVES") || b.equals("HAY_BLOCK") || b.equals("TARGET")
                    || b.contains("SPONGE")
                    // 1.21 hoe-mineable
                    || b.equals("MOSS_BLOCK") || b.equals("MOSS_CARPET")
                    || b.equals("PALE_MOSS_BLOCK") || b.equals("PALE_MOSS_CARPET")
                    || b.equals("NETHER_WART_BLOCK") || b.equals("WARPED_WART_BLOCK")
                    || b.equals("SHROOMLIGHT") || b.contains("SCULK")
                    || b.equals("DRIED_KELP_BLOCK");
        }
        if (name.equals("SHEARS")) {
            return b.contains("LEAVES") || b.contains("WOOL") || b.contains("COBWEB")
                    || b.equals("VINE") || b.equals("GLOW_LICHEN") || b.contains("CARPET")
                    || b.contains("HANGING_ROOTS") || b.equals("NETHER_SPROUTS");
        }
        return false;
    }

    private static double baseSpeed(ItemStack tool, Material block) {
        if (tool == null) return 1.0;
        String n = tool.getType().name();
        if (n.startsWith("WOODEN_") || n.startsWith("GOLDEN_")) return 2.0;
        if (n.startsWith("STONE_"))    return 4.0;
        if (n.startsWith("IRON_"))     return 6.0;
        if (n.startsWith("DIAMOND_"))  return 8.0;
        if (n.startsWith("NETHERITE_")) return 9.0;
        if (n.equals("SHEARS"))        return 1.5;
        return 1.0;
    }

    private static boolean hasAquaAffinity(Player p) {
        try {
            ItemStack helm = p.getInventory().getHelmet();
            return helm != null && helm.getEnchantmentLevel(Enchantment.AQUA_AFFINITY) > 0;
        } catch (Throwable t) { return false; }
    }

    private static boolean hasConduitPower(Player p) {
        try {
            return p.hasPotionEffect(org.bukkit.potion.PotionEffectType.CONDUIT_POWER);
        } catch (Throwable t) { return false; }
    }

    @Override
    public void cleanup(UUID uuid) {
        super.cleanup(uuid);
        pending.remove(uuid);
        lastSeq.remove(uuid);
    }
}
