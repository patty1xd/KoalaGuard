package com.koalaguard.checks.movement;

import com.koalaguard.KoalaGuard;
import com.koalaguard.check.MovementCheck;
import com.koalaguard.data.PlayerData;
import com.koalaguard.util.MovementPredictor;
import org.bukkit.entity.Player;

/**
 * NoFall — targets the real cheat (LiquidBounce/FDP NoFall: spoof
 * onGround=true mid-air, or AirJump). The client's OWN flying-packet
 * onGround flag (d.clientGround) claims ground while the server knows there
 * is no block under them and they are still descending with accumulated
 * fall. Flag + lagback so the negated fall damage is restored.
 */
public final class NoFallCheck extends MovementCheck {

    public NoFallCheck(KoalaGuard plugin) {
        super(plugin, "nofall", "Spoofing ground state to negate fall damage");
    }

    @Override
    public void handle(PlayerData d, Player player) {
        if (MovementPredictor.verticalUnsafe(d, player)) { d.setBuffer(k("fall"), 0); return; }

        if (d.deltaY < -0.08 && !d.serverGround && !d.nearGround) {
            d.addBuffer(k("fall"), -d.deltaY, 80.0);          // accumulate true fall
        }
        double fallen = d.buffer(k("fall"));

        boolean clientClaimsGround = d.clientGround;
        boolean serverSaysAir = !d.serverGround && !d.nearGround;

        if (clientClaimsGround && serverSaysAir && d.deltaY < -0.08 && fallen >= 3.5) {
            double buf = d.addBuffer(k("b"), 3.0, 9.0);
            if (buf >= 5.0) {
                failAndSetback(d, player, String.format("claimed ground after %.1f-block fall", fallen));
                d.setBuffer(k("b"), 1.0);
            }
            d.setBuffer(k("fall"), 0);
            return;
        }
        if (d.serverGround || d.nearGround) { d.setBuffer(k("fall"), 0); d.subBuffer(k("b"), 1.0); }
    }
}
