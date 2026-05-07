package com.koalaguard.logging;

import com.koalaguard.KoalaGuard;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

public final class DiscordWebhookNotifier {
  private final KoalaGuard plugin;
  private final HttpClient client =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

  public DiscordWebhookNotifier(KoalaGuard plugin) {
    this.plugin = plugin;
  }

  public void send(String content) {
    String url = plugin.getConfig().getString("discord.webhook-url", "");
    if (url == null || url.isBlank()) return;

    String body = content == null ? "" : content;
    if (body.length() > 1900) body = body.substring(0, 1900) + "...";

    String json = "{\"content\":" + quote(body) + "}";

    try {
      HttpRequest req =
          HttpRequest.newBuilder()
              .uri(URI.create(url.trim()))
              .timeout(Duration.ofSeconds(8))
              .header("Content-Type", "application/json")
              .POST(HttpRequest.BodyPublishers.ofString(json))
              .build();

      client
          .sendAsync(req, HttpResponse.BodyHandlers.discarding())
          .exceptionally(
              ex -> {
                plugin.getLogger().warning("Discord webhook failed: " + ex.getMessage());
                return null;
              });
    } catch (Exception e) {
      plugin.getLogger().warning("Discord webhook failed: " + e.getMessage());
    }
  }

  private static String quote(String s) {
    return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
  }
}

