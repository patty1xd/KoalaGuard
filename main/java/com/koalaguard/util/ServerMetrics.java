package com.koalaguard.util;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.lang.reflect.Method;

public final class ServerMetrics {
  private final Method getPingMethod;

  public ServerMetrics() {
    Method m = null;
    try {
      m = Player.class.getMethod("getPing"); // Paper
    } catch (Exception ignored) {
    }
    this.getPingMethod = m;
  }

  public int pingMs(Player player) {
    if (player == null) return -1;
    if (getPingMethod != null) {
      try {
        Object v = getPingMethod.invoke(player);
        if (v instanceof Integer i) return i;
      } catch (Exception ignored) {
      }
    }
    return -1;
  }

  public double tps1m() {
    try {
      double[] tps = Bukkit.getServer().getTPS(); // Paper
      if (tps != null && tps.length > 0) return tps[0];
    } catch (Throwable ignored) {
    }
    return 20.0;
  }
}

