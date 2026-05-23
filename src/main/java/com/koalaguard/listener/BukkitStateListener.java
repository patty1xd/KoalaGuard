package com.koalaguard.listener;

import com.koalaguard.KoalaGuard;
import com.koalaguard.data.PlayerData;
import com.koalaguard.engine.state.CombatState;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityResurrectEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.*;
import org.bukkit.inventory.ItemStack;

/**
 * The ONLY Bukkit-event surface left. It does no detection — it just owns the
 * {@link PlayerData} lifecycle, enforces bans at login, and stamps the
 * grace/knockback facts that are simply not available on the packet wire
 * (server-applied velocity, world change, resurrection). All judgement happens
 * later in the engine against reconstructed state.
 */
public final class BukkitStateListener implements Listener {

    private final KoalaGuard plugin;

    public BukkitStateListener(KoalaGuard plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onLogin(PlayerLoginEvent event) {
        Player p = event.getPlayer();
        if (plugin.getBanManager().isBanned(p.getUniqueId())) {
            String reason = plugin.getBanManager().getBanReason(p.getUniqueId());
            String expiry = plugin.getBanManager().getBanExpiry(p.getUniqueId());
            event.disallow(PlayerLoginEvent.Result.KICK_BANNED,
                    plugin.getBanManager().banScreen(reason, expiry));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        plugin.getDataManager().create(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        plugin.getDataManager().remove(event.getPlayer().getUniqueId());
        plugin.getViolationManager().clearPlayer(event.getPlayer().getUniqueId());
        plugin.getEngine().cleanup(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        PlayerData d = plugin.getDataManager().get(event.getPlayer());
        if (d == null) return;
        d.lastTeleportMs = System.currentTimeMillis();
        d.engine.moveInit = false;             // re-baseline reconstruction
        if (event.getFrom().getWorld() != null && event.getTo() != null
                && !event.getFrom().getWorld().equals(event.getTo().getWorld())) {
            d.lastWorldChangeMs = System.currentTimeMillis();
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldChange(PlayerChangedWorldEvent event) {
        PlayerData d = plugin.getDataManager().get(event.getPlayer());
        if (d != null) {
            d.lastWorldChangeMs = System.currentTimeMillis();
            d.engine.moveInit = false;
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onVelocity(PlayerVelocityEvent event) {
        PlayerData d = plugin.getDataManager().get(event.getPlayer());
        if (d == null) return;
        d.lastVelocityMs = System.currentTimeMillis();
        d.pendingVelocity = event.getVelocity().clone();
        d.pendingVelocityMs = d.lastVelocityMs;
        CombatState c = d.engine.combat;
        c.pendingKnockback = event.getVelocity().clone();
        c.knockbackTick = d.engine.tick;
        c.knockbackConsumed = false;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player p)) return;
        PlayerData d = plugin.getDataManager().get(p);
        if (d == null) return;
        d.lastDamageMs = System.currentTimeMillis();
        d.engine.combat.lastDamageTakenTick = d.engine.tick;
        d.engine.combat.lastDamageTakenNanos = System.nanoTime();

        // FIRST-totem trigger: damage taken while the off hand has NO totem.
        // (A totem already in the off hand is the pop path — handled by
        // onResurrect, which fires before the totem is consumed so the off
        // hand still reads TOTEM here and this does not stamp.)
        if (p.getInventory().getItemInOffHand().getType() != Material.TOTEM_OF_UNDYING) {
            var inv = d.engine.inv;
            inv.vulnNanos = System.nanoTime();
            inv.vulnTick = d.engine.tick;
            inv.vulnSeq++;
        }
    }

    /**
     * Fake-critical hit PREVENTION — runs at HIGHEST priority.
     *
     * Vanilla criticals require: {@code fallDistance > 0 && !onGround &&
     * !ladder && !water && !blindness && !passenger && !sprinting}. Every
     * critical cheat variant (LiquidBounce documents six — NoGround, Packet
     * /OnGroundOnly/PositionAndOnGround/LookAndOnGround/Full, Jump, MiniJump,
     * Blink, Timer; Wurst/Meteor/Future/Sigma have functional dupes) gets the
     * server to satisfy that condition while the attacker is, geometrically,
     * still on solid ground. They lie via packet flags (onGround=false) or
     * tiny Y-oscillations (0.0001 .. 0.0625) — all of which leave the engine's
     * collision-true {@code simGround} intact for the recent frames.
     *
     * Solution: if the server believes a crit applied (it scaled the damage
     * by the 1.0-1.5 multiplier) but the engine's last N frames were on
     * geometric ground, undo the crit multiplier. A legit jump-crit produces
     * simGround=false on the apex frames and is untouched.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onFakeCritPrevent(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player atk)) return;
        PlayerData d = plugin.getDataManager().get(atk);
        if (d == null) return;

        // Crit precondition — server applied the bonus only if it thought the
        // attacker was airborne and falling. fallDistance reflects either real
        // or packet-spoofed motion (the cheat path).
        if (atk.getFallDistance() <= 0.0f) return;
        if (atk.isOnGround()) return;          // server itself disagrees — no crit possible

        // Engine ground truth: walk the last N reconstructed frames. If ANY
        // recent frame was airborne by geometry (simGround=false AND dy < 0
        // or dy >= jump impulse), it's a legit jump — leave it.
        int look = (int) plugin.getConfig().getLong("checks.criticals.cap-frames", 4);
        boolean realFall = false;
        int groundFrames = 0;
        for (var f : d.engine.recentFrames(look)) {
            if (!f.simGround && (f.dy < -0.05 || f.dy > 0.30)) { realFall = true; break; }
            if (f.simGround) groundFrames++;
        }
        if (realFall) return;
        if (groundFrames < Math.max(2, look / 2)) return;   // not enough info, be safe

        // Strip the crit multiplier. Mojang scales by uniform(1.0, 1.5); use
        // a 1.5 divider so we land at the NON-crit floor — safer to slightly
        // under-damage a fake-crit attack than to leave any bonus intact.
        double dealt = event.getDamage();
        event.setDamage(dealt / 1.5);
        d.engine.combat.lastFakeCritNanos = System.nanoTime();
        d.engine.combat.lastFakeCritCount++;
        d.engine.combat.lastFakeCritDetail = String.format(
                "server fall=%.2f onGround=%b BUT engine groundFrames=%d/%d",
                (double) atk.getFallDistance(), atk.isOnGround(), groundFrames, look);
        if (plugin.getConfig().getBoolean("debug", false)) {
            plugin.getLogger().info("[fake-crit] " + atk.getName()
                    + " dmg " + String.format("%.2f→%.2f", dealt, dealt / 1.5)
                    + " :: " + d.engine.combat.lastFakeCritDetail);
        }
    }

    /**
     * Shield bypass via BACKSTAB-TELEPORT prevention — runs at HIGHEST.
     *
     * Vanilla shield blocks anything coming from within the front-180° arc of
     * the defender's yaw (Mojang: dot(facing, victim→attacker projected onto
     * XZ) < 0). A "Backstab" / displacement killaura (Meteor + addons, certain
     * Liquidbounce/Future variants) gets around it by:
     *   1. sending a position packet that puts the attacker behind the
     *      defender for ONE TICK,
     *   2. sending the attack — server sees attacker behind ⇒ shield bypassed
     *      ⇒ damage applies,
     *   3. sending a position packet back to the real spot.
     * This is invisible to the auto-axe-swap check (no swap happens). It is
     * also invisible to ClickTp / Prediction when the displacement is huge
     * enough to be eaten by the engine's >8-block teleport-grace reset.
     *
     * We catch it deterministically at the DAMAGE event: at the instant the
     * attack lands the engine's RECONSTRUCTED recent-tick attacker positions
     * are checked. If they all lie in the defender's FRONT cone but the
     * server's current attacker location is BEHIND, the attacker teleported
     * behind for the hit. Cancel the damage (as the shield would have done)
     * and flag.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onShieldBackstab(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player atk)) return;
        if (!(event.getEntity() instanceof Player victim)) return;
        if (!victim.isBlocking()) return;                        // shield not up

        PlayerData ad = plugin.getDataManager().get(atk);
        if (ad == null) return;

        // Defender's facing direction projected onto XZ.
        float vyaw = victim.getLocation().getYaw();
        double fx = -Math.sin(Math.toRadians(vyaw));
        double fz =  Math.cos(Math.toRadians(vyaw));

        org.bukkit.Location vloc = victim.getLocation();

        // 1) Current attacker location dot product — Mojang computes this and
        //    decided the attack BYPASSED the shield (else this damage event
        //    wouldn't have fired with non-zero damage).
        double cx = atk.getLocation().getX() - vloc.getX();
        double cz = atk.getLocation().getZ() - vloc.getZ();
        double curDot = fx * cx + fz * cz;
        if (curDot >= 0) return;             // attacker actually in front; vanilla geometry already blocked

        // 2) Reconstructed attacker positions over the last few ticks. The
        //    cheat's two signatures (vs sprint-around):
        //      (a) "frontFrames" — recent reconstructed frames in the defender's
        //          front cone (would-be-shielded). A legit player who sprinted
        //          around has already accumulated many BEHIND frames before
        //          attacking — they cross the cone gradually.
        //      (b) "jumpToCurrent" — the engine-reconstructed position vs the
        //          server's current attacker position. A single-tick teleport
        //          that's eaten by the engine's >8 reset (frame skipped) leaves
        //          a HUGE gap here. Legit movement is bounded by sprint speed.
        int frontFrames = 0, behindFrames = 0;
        int scan = (int) plugin.getConfig().getLong("checks.shieldbypass.backstab-scan-frames", 6);
        org.bukkit.Location aloc = atk.getLocation();
        double maxFrameToCurrent = 0;          // biggest gap between any recent frame and current server pos
        double maxFrameToFrame = 0;            // biggest single-tick jump within the window
        var frames = ad.engine.recentFrames(scan);
        double prevX = Double.NaN, prevZ = Double.NaN;
        for (var f : frames) {
            double dx = f.x - vloc.getX();
            double dz = f.z - vloc.getZ();
            double dot = fx * dx + fz * dz;
            if (dot >= 0) frontFrames++;
            else          behindFrames++;
            double gx = f.x - aloc.getX();
            double gz = f.z - aloc.getZ();
            double gap = Math.sqrt(gx * gx + gz * gz);
            if (gap > maxFrameToCurrent) maxFrameToCurrent = gap;
            if (!Double.isNaN(prevX)) {
                double dfx = f.x - prevX, dfz = f.z - prevZ;
                double step = Math.sqrt(dfx * dfx + dfz * dfz);
                if (step > maxFrameToFrame) maxFrameToFrame = step;
            }
            prevX = f.x; prevZ = f.z;
        }
        int minFront = (int) plugin.getConfig().getLong("checks.shieldbypass.min-front-frames", 3);
        double maxLegitGap = plugin.getConfig().getDouble("checks.shieldbypass.max-legit-gap", 2.5);
        double maxLegitStep = plugin.getConfig().getDouble("checks.shieldbypass.max-legit-step", 1.0);

        boolean teleportSignature = maxFrameToCurrent > maxLegitGap
                || maxFrameToFrame > maxLegitStep;
        boolean stableFront = frontFrames >= minFront && behindFrames <= scan / 2;

        // Require BOTH the "looked-shielded recently" signal AND a teleport
        // gap (or huge single step) — a player sprinting around naturally
        // produces neither (gap and step stay small and they were already
        // behind on most frames before the hit).
        if (!stableFront || !teleportSignature) return;

        event.setCancelled(true);                  // shield holds — the cheat does not
        var c = ad.engine.combat;
        c.lastBackstabBlockedNanos = System.nanoTime();
        c.lastBackstabBlockedCount++;
        c.lastBackstabDetail = String.format(
                "front-frames=%d/%d, curDot=%.2f, atk=%.1f,%.1f vs vict=%.1f,%.1f, vyaw=%.0f",
                frontFrames, frontFrames + behindFrames, curDot,
                atk.getLocation().getX(), atk.getLocation().getZ(),
                vloc.getX(), vloc.getZ(), vyaw);
        if (plugin.getConfig().getBoolean("debug", false)) {
            plugin.getLogger().info("[shield-backstab] " + atk.getName()
                    + " hit shielded " + victim.getName() + " :: " + c.lastBackstabDetail);
        }
    }

    /**
     * Mace damage HARD CAP — runs at HIGHEST priority BEFORE damage applies.
     *
     * The 1.21 mace deals enormous smash damage ONLY when the attacker has a
     * real {@code fallDistance}; a "MaceDMG" cheat (Wurst, Meteor addons, etc.)
     * either:
     *   • spoofs the server's fallDistance by sending a Y oscillation packet
     *     sequence (jump-fake then fall-fake) before the hit, or
     *   • directly tricks the formula via packet-level position lying.
     * After the cheat fires the server's own getFallDistance() value is FAKE.
     * Detecting it after damage = "yeah it dropped me, here's a flag" — not
     * good enough. Instead we recompute the legitimate damage from the
     * engine's RECONSTRUCTED ground/fall state (the cheat cannot fake the
     * world's collision shape, only its own position packets — and the engine
     * has, for the last N ticks, asked the WORLD whether each reconstructed
     * position was actually on solid ground via simGround) and CAP the event
     * to that. End result: on a real fall the cap is far above the smash, so
     * legit smashes pass untouched; on the ground the cap is the mace's
     * vanilla base damage and the cheat literally cannot exceed it.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onMaceCap(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player atk)) return;
        PlayerData d = plugin.getDataManager().get(atk);
        if (d == null) return;
        if (atk.getInventory().getItemInMainHand().getType() != Material.MACE) return;

        double dealt = event.getDamage();
        double engineFall = engineRealFall(d);
        double legitCap = legitMaceCap(atk, engineFall);

        if (dealt > legitCap + 0.01) {
            event.setDamage(legitCap);
            // Flag the cheat through the existing pipeline so staff see what
            // was capped — and the wider mace check still gets the signal.
            d.engine.combat.lastMaceCapPrevented++;
            d.engine.combat.lastMaceCapPreventedNanos = System.nanoTime();
            if (plugin.getConfig().getBoolean("debug", false)) {
                plugin.getLogger().info("[mace-cap] " + atk.getName()
                        + " dealt=" + String.format("%.2f", dealt)
                        + " capped=" + String.format("%.2f", legitCap)
                        + " engineFall=" + String.format("%.2f", engineFall)
                        + " serverFall=" + String.format("%.2f", (double) atk.getFallDistance()));
            }
        }

        CombatState c = d.engine.combat;
        c.lastMaceDamage = event.getDamage();
        c.lastMaceFallDistance = atk.getFallDistance();
        c.lastMaceOnGround = atk.isOnGround();
        c.lastMaceHitNanos = System.nanoTime();
    }

    /**
     * Reconstructed fall distance from the engine's frame history — counts
     * accumulated downward motion while the player has been actually
     * unsupported by world collision (simGround=false). Resets the moment a
     * ground-supported frame is seen. The cheat can lie about its packets but
     * cannot lie about the world: a player standing on dirt has simGround=true
     * on every recent frame regardless of what they claim with their Y.
     */
    private double engineRealFall(PlayerData d) {
        double fall = 0;
        boolean started = false;
        // recentFrames is newest-first; walk newest→oldest, stop at the
        // most recent supported (simGround) tick.
        for (var f : d.engine.recentFrames(40)) {
            if (f.simGround) {
                if (started) break;                 // landed — fall ends here
                continue;
            }
            if (f.dy < 0) {
                fall += -f.dy;
                started = true;
            }
        }
        return fall;
    }

    /**
     * Vanilla mace damage ceiling for the attacker's REAL fall + their mace's
     * known enchants. Generous +20% safety margin so genuine network jitter
     * never accidentally caps a legit smash.
     */
    private double legitMaceCap(Player atk, double realFall) {
        org.bukkit.inventory.ItemStack mace = atk.getInventory().getItemInMainHand();
        int sharpness = 0, density = 0;
        try {
            sharpness = mace.getEnchantmentLevel(org.bukkit.enchantments.Enchantment.SHARPNESS);
        } catch (Throwable ignored) { }
        try {
            // Density is the mace smash-bonus enchant (1.21+).
            org.bukkit.enchantments.Enchantment dens = org.bukkit.Registry.ENCHANTMENT.get(
                    org.bukkit.NamespacedKey.minecraft("density"));
            if (dens != null) density = mace.getEnchantmentLevel(dens);
        } catch (Throwable ignored) { }

        // Vanilla mace base 6.0, +1.0 per sharpness level (sharpness adds
        // 0.5*level+0.5 = 1.0 at level 1, 1.5 at 2, etc. — using the safer
        // upper bound 1.0 per level + 0.5 here, total floor is generous).
        double base = 6.0 + 0.5 + sharpness * 1.0;

        // Smash bonus only if the real fall is at least 1.5 blocks.
        double smash = 0;
        if (realFall >= 1.5) {
            double f = realFall;
            smash += 4.0 * Math.min(f, 3.0);                      // first 3 blocks
            if (f > 3.0) smash += 2.0 * Math.min(f - 3.0, 5.0);   // next 5
            if (f > 8.0) smash += 1.0 * (f - 8.0);                // remainder
            smash += density * 0.5 * f;                           // density enchant
        }
        return (base + smash) * 1.2;     // +20% margin for network jitter
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onResurrect(EntityResurrectEvent event) {
        if (!(event.getEntity() instanceof Player p)) return;
        PlayerData d = plugin.getDataManager().get(p);
        if (d == null) return;
        // EXACT pop moment — fires before any refill, so an instant autototem
        // cannot hide it. seq is a pure counter (advances even while perfectly
        // still); popConf/nanos give a movement-independent "since pop" bound.
        // NOTE: no stack guard. A legit player carrying a totem STACK produces
        // ZERO click packets (the stack just decrements), so the packet-
        // behaviour signals below never fire for them — that, not skipping the
        // cycle, is what makes the two-totem case false-positive proof. The old
        // amount>=2 skip is exactly why stack-based autototems were invisible.
        var inv = d.engine.inv;
        inv.totemConsumedTick = d.engine.tick;
        inv.totemPopConf = d.confirmedTransactions;
        inv.totemPopNanos = System.nanoTime();
        inv.totemPopSeq++;
        inv.awaitingTotemTransition = true;
        inv.popPending = true;
    }

    /**
     * The server fires this whenever the autototem's ClickSlot is processed —
     * regardless of how the cheat crafted the packet. We record a re-equip
     * only when a totem actually goes to the off hand AND a pop is pending, so
     * a legit stack carry (no click at all) is impossible to flag.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player p)) return;
        PlayerData d = plugin.getDataManager().get(p);
        if (d == null) return;
        var inv = d.engine.inv;
        if (!inv.popPending) return;

        boolean totemToOffhand = false;
        // Direct place into the off-hand slot (Meteor: pickup then click 45).
        if (event.getRawSlot() == 45) {
            ItemStack c = event.getCursor();
            ItemStack cur = event.getCurrentItem();
            if ((c != null && c.getType() == Material.TOTEM_OF_UNDYING)
                    || (cur != null && cur.getType() == Material.TOTEM_OF_UNDYING)) {
                totemToOffhand = true;
            }
        }
        // Hotbar→offhand swap (F) issued from inside the inventory.
        if (event.getClick() == ClickType.SWAP_OFFHAND) {
            ItemStack cur = event.getCurrentItem();
            if (cur != null && cur.getType() == Material.TOTEM_OF_UNDYING) totemToOffhand = true;
        }
        if (totemToOffhand) recordReequip(d);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSwapHands(PlayerSwapHandItemsEvent event) {
        PlayerData d = plugin.getDataManager().get(event.getPlayer());
        if (d == null) return;
        var inv = d.engine.inv;
        if (!inv.popPending) return;
        ItemStack off = event.getOffHandItem();
        if (off != null && off.getType() == Material.TOTEM_OF_UNDYING) recordReequip(d);
    }

    private void recordReequip(PlayerData d) {
        var inv = d.engine.inv;
        inv.reequipConf = d.confirmedTransactions;
        inv.reequipNanos = System.nanoTime();
        long sinceAtkMs = (System.nanoTime() - d.engine.combat.lastAttackNanos) / 1_000_000L;
        inv.reequipBusy = sinceAtkMs >= 0 && sinceAtkMs < 1500;   // GUI cannot be open while attacking
        inv.reequipSeq++;
        inv.popPending = false;
    }

    /**
     * Authoritative block-place capture. Stores the ACTUAL placed material +
     * the player's location & rotation at the place instant into a per-player
     * ring. AutoWebCheck (and any future place-aware check) reads from this
     * ring instead of trying to read the spoofable {@code inv.mainHand}
     * inventory mirror — which a swap-in / place / swap-back cheat clears
     * before the per-tick mirror runs. Fires at MONITOR so it sees only
     * actually-accepted placements.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        Player p = event.getPlayer();
        PlayerData d = plugin.getDataManager().get(p);
        if (d == null) return;
        var loc = p.getLocation();
        var b = event.getBlockPlaced();
        d.engine.pushPlace(new com.koalaguard.engine.state.PlayerState.PlaceRecord(
                System.nanoTime(),
                b.getType(),
                b.getX(), b.getY(), b.getZ(),
                loc.getX(), loc.getY(), loc.getZ(),
                loc.getPitch(), loc.getYaw()));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerRespawnEvent event) {
        PlayerData d = plugin.getDataManager().get(event.getPlayer());
        if (d == null) return;
        long now = System.currentTimeMillis();
        if (d.lastDeathMs > 0) {
            d.lastRespawnGapMs = now - d.lastDeathMs;
        }
        d.lastRespawnMs = now;
        d.engine.moveInit = false;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent event) {
        PlayerData d = plugin.getDataManager().get(event.getEntity());
        if (d != null) d.lastDeathMs = System.currentTimeMillis();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onGameMode(PlayerGameModeChangeEvent event) {
        PlayerData d = plugin.getDataManager().get(event.getPlayer());
        if (d != null) d.gamemodeChangeMs = System.currentTimeMillis();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMoveState(PlayerMoveEvent event) {
        if (event.getTo() == null) return;
        Player p = event.getPlayer();
        PlayerData d = plugin.getDataManager().get(p);
        if (d == null) return;
        long now = System.currentTimeMillis();
        if (p.isRiptiding()) d.lastRiptideMs = now;
        if (p.isGliding()) d.elytraMs = now;
        Material at = event.getTo().getBlock().getType();
        if (at == Material.BUBBLE_COLUMN) d.bubbleColumnMs = now;
        if (event.getTo().getY() > event.getFrom().getY()) {
            Material below = event.getTo().clone().subtract(0, 1, 0).getBlock().getType();
            if (below == Material.SLIME_BLOCK) d.slimeBounceMs = now;
            if (below == Material.SLIME_BLOCK || below.name().endsWith("_BED")) d.lastSlimeOrBedMs = now;
        }

        // Reliable setback anchor. Every movement setback rubber-bands to
        // d.lastValidLocation; previously only the engine frame loop set it,
        // and only on a ground-supported reconstructed frame — so checks that
        // flag while airborne / clipping / teleporting (everything except
        // Prediction, which flags during normal ground movement) found it null
        // and the setback silently no-opped into an alert. Capture the last
        // genuinely on-solid-ground server position here, from the
        // authoritative move event, BEFORE any clip/teleport, outside grace.
        if (!d.setbackPending
                && now - d.lastTeleportMs   > 1500
                && now - d.lastVelocityMs   > 1500
                && now - d.lastRespawnMs    > 2500
                && now - d.lastWorldChangeMs > 4000
                && now - d.joinMs           > 3500
                && !p.isFlying() && !p.isGliding() && !p.isInsideVehicle()
                && !p.isInWater()) {
            var to = event.getTo();
            if (to.clone().subtract(0, 0.2, 0).getBlock().getType().isSolid()) {
                d.lastValidLocation = to.clone();
            }
        }
    }
}
