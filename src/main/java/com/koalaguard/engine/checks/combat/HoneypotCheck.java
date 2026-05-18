package com.koalaguard.engine.checks.combat;

import com.koalaguard.KoalaGuard;
import com.koalaguard.check.CheckCategory;
import com.koalaguard.engine.check.CheckContext;
import com.koalaguard.engine.check.SimCheck;
import com.koalaguard.engine.honeypot.FakePlayer;
import com.koalaguard.engine.packet.CapturedPacket;
import com.koalaguard.engine.packet.PacketKind;
import com.koalaguard.engine.state.PositionFrame;
import com.koalaguard.engine.util.Combat;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Layer 3 — killaura honeypot. INDEPENDENT check.
 *
 * In a PvP context two invisible trap entities are spawned right next to the
 * player, only the player can't see either:
 *
 *  • an invisible, non-colliding ArmorStand (real server entity) — catches
 *    all-entity / mob / aggressive auras;
 *  • a packet-level GHOST PLAYER sent only to this client ({@link FakePlayer})
 *    — a player-TYPE entity, so a kill-aura with a strict "players-only"
 *    target filter (the ArmorStand's blind spot) still attacks it.
 *
 * Neither is renderable by a vanilla client and neither can be raytraced by a
 * human, so ANY InteractEntity packet targeting either id is a ~100 %
 * confidence kill-aura — zero false positives by construction. The ghost is
 * defensively built; if the server-wrapper API differs on this build it simply
 * yields no ghost and the ArmorStand still runs.
 *
 * Runs on the main thread (engine tick) so Bukkit entity spawn/remove is safe.
 */
public final class HoneypotCheck extends SimCheck {

    private static final class H {
        ArmorStand stand;
        int standId = -1;
        FakePlayer.Ghost ghost;
        boolean active;
        long spawnNanos;
        long despawnAtMs;
        long nextSpawnMs;
        double hx, hy, hz;          // honeypot body-centre (look-gate)
    }

    private final Map<UUID, H> pots = new ConcurrentHashMap<>();

    public HoneypotCheck(KoalaGuard plugin) {
        super(plugin, "honeypot", CheckCategory.COMBAT, "Attacked an invisible honeypot entity");
    }

    @Override
    public void onTick(CheckContext ctx) {
        Player player = ctx.player;
        H h = pots.computeIfAbsent(ctx.data.getUuid(), k -> new H());
        long now = System.currentTimeMillis();

        // ── Detection: an interaction with EITHER trap's entity id ──
        if (h.active) {
            int ghostId = h.ghost != null ? h.ghost.entityId : Integer.MIN_VALUE;
            for (CapturedPacket p : ctx.state.log.recent(160)) {
                if (p.kind != PacketKind.INTERACT_ENTITY) continue;
                if (p.recvNanos < h.spawnNanos) continue;
                boolean hitStand = h.standId >= 0 && p.intA == h.standId;
                boolean hitGhost = p.intA == ghostId;
                if (!hitStand && !hitGhost) continue;

                // CRITICAL FP guard: an invisible entity still has a client
                // hitbox, so a legit player who looks toward it (building,
                // mining, fighting the real opponent) and left-clicks will
                // raytrace it. Reconstruct the look at the hit tick — if the
                // crosshair was actually pointing at the honeypot it COULD be
                // a real (accidental) raytrace, so DO NOT flag. Only an
                // entity-iterating aura attacks while looking well away from it.
                PositionFrame f = ctx.state.frameAtOrBefore(p.tickIndex);
                double[] el = Combat.eyeLook(f, player);
                double tx = h.hx - el[0], ty = h.hy - el[1], tz = h.hz - el[2];
                double dlen = Math.sqrt(tx * tx + ty * ty + tz * tz);
                if (dlen > 1e-3) {
                    Vector look = Combat.lookVector((float) el[3], (float) el[4]);
                    double dot = (look.getX() * tx + look.getY() * ty + look.getZ() * tz) / dlen;
                    double ang = Math.toDegrees(Math.acos(Math.max(-1.0, Math.min(1.0, dot))));
                    if (ang < cfgD("max-aim-deg", 50.0)) continue;   // could be legit
                }

                diverge(ctx, cfgD("score", 20.0), cfgD("threshold", 9.0),
                        cfgI("min-streak", 1),
                        "attacked unrenderable honeypot " + (hitGhost ? "ghost-player" : "entity")
                                + " while looking away (id " + p.intA + ")", false);
                armCombatCancel(ctx);
                despawn(player, h);
                h.nextSpawnMs = now + cfgL("cooldown-ms", 12_000L);
                return;
            }
            if (now > h.despawnAtMs) {
                despawn(player, h);
                h.nextSpawnMs = now + cfgL("cooldown-ms", 12_000L);
            }
            return;
        }

        // ── Spawn: only in a real PvP context, rate-limited ──
        if (now < h.nextSpawnMs) return;
        if (player.isDead() || player.isInsideVehicle()) return;
        GameMode gm = player.getGameMode();
        if (gm == GameMode.CREATIVE || gm == GameMode.SPECTATOR) return;
        if (player.getWorld() == null) return;

        double r = cfgD("combat-radius", 7.0);
        boolean pvp = false;
        for (Player o : player.getWorld().getPlayers()) {
            if (o == player) continue;
            if (o.getGameMode() == GameMode.SPECTATOR) continue;
            if (o.getLocation().distanceSquared(player.getLocation()) <= r * r) { pvp = true; break; }
        }
        if (!pvp) return;

        Location base = player.getLocation();
        double dist = cfgD("spawn-distance", 1.6);
        double rad = Math.toRadians(base.getYaw());
        // Slightly BEHIND the player — well within any kill-aura range, never
        // where a human aims.
        double bx = Math.sin(rad) * dist;
        double bz = -Math.cos(rad) * dist;
        Location loc = base.clone().add(bx, 0.2, bz);

        try {
            ArmorStand stand = player.getWorld().spawn(loc, ArmorStand.class, a -> {
                a.setVisible(false);
                a.setGravity(false);
                a.setMarker(true);           // ZERO hitbox — a vanilla client
                                             // physically cannot raytrace it,
                                             // so no legit click can ever hit
                                             // it; an entity-iterating aura
                                             // (attacks by id) still does.
                a.setCollidable(false);      // never obstruct players
                a.setSmall(true);
                a.setBasePlate(false);
                a.setArms(false);
                a.setSilent(true);
                a.setPersistent(false);
                a.setRemoveWhenFarAway(true);
                a.setInvulnerable(true);
                a.addScoreboardTag("kg_honeypot");
            });
            h.stand = stand;
            h.standId = stand.getEntityId();
        } catch (Throwable t) {
            h.stand = null;
            h.standId = -1;
        }

        if (cfgB("fake-player", true)) {
            h.ghost = FakePlayer.spawn(plugin, player,
                    loc.getX(), loc.getY(), loc.getZ(), base.getYaw());
        }

        if (h.standId >= 0 || h.ghost != null) {
            h.active = true;
            h.spawnNanos = System.nanoTime();
            h.despawnAtMs = now + cfgL("lifetime-ms", 2500L);
            h.hx = loc.getX();
            h.hy = loc.getY() + 1.0;        // body centre (stand or ghost)
            h.hz = loc.getZ();
        } else {
            h.nextSpawnMs = now + cfgL("cooldown-ms", 12_000L);
        }
    }

    private void despawn(Player player, H h) {
        try {
            if (h.stand != null && h.stand.isValid()) h.stand.remove();
        } catch (Throwable ignored) { }
        if (h.ghost != null && player != null) FakePlayer.despawn(player, h.ghost);
        h.stand = null;
        h.standId = -1;
        h.ghost = null;
        h.active = false;
    }

    @Override
    public void cleanup(UUID uuid) {
        super.cleanup(uuid);
        H h = pots.remove(uuid);
        // No viewer needed on quit — the client connection is gone, so the
        // ghost vanishes with it; just drop the ArmorStand.
        if (h != null) despawn(null, h);
    }
}
