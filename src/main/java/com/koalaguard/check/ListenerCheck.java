package com.koalaguard.check;

import com.koalaguard.KoalaGuard;
import org.bukkit.event.Listener;

/**
 * For event-driven checks (block place/break, inventory, fishing, totem …)
 * that aren't tied to the movement/combat tick pipelines. They still extend
 * {@link Check} so they share config, alerts and the buffer model, but they
 * register their own Bukkit event handlers.
 */
public abstract class ListenerCheck extends Check implements Listener {

    protected ListenerCheck(KoalaGuard plugin, String name, CheckCategory category, String description) {
        super(plugin, name, category, description);
    }
}
