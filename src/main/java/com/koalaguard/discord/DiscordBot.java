package com.koalaguard.discord;

import com.koalaguard.KoalaGuard;
import com.koalaguard.alert.Alert;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.requests.GatewayIntent;
import net.dv8tion.jda.api.utils.cache.CacheFlag;

import java.time.Instant;
import java.util.EnumSet;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Forwards alerts to Discord as rich embeds (player head thumbnail,
 * colour-coded by severity, structured fields) instead of plain text.
 *
 * Setup: create a bot, enable it, invite with Send Messages + Embed Links,
 * then set discord.bot-token and discord.alert-channel-id in config.yml.
 */
public final class DiscordBot {

    private final KoalaGuard plugin;
    private volatile JDA jda;
    private volatile String alertChannelId;
    private final Queue<Alert> pending = new ConcurrentLinkedQueue<>();

    public DiscordBot(KoalaGuard plugin) {
        this.plugin = plugin;
    }

    public void start() {
        String token = plugin.getConfig().getString("discord.bot-token", "");
        alertChannelId = plugin.getConfig().getString("discord.alert-channel-id", "");

        if (token == null || token.isBlank()) {
            plugin.getLogger().info("[Discord] bot-token not set — Discord alerts disabled.");
            return;
        }
        Thread t = new Thread(() -> {
            try {
                jda = JDABuilder.createLight(token, EnumSet.noneOf(GatewayIntent.class))
                        .disableCache(EnumSet.allOf(CacheFlag.class))
                        .build()
                        .awaitReady();
                plugin.getLogger().info("[Discord] Connected as " + jda.getSelfUser().getName());
                Alert a;
                while ((a = pending.poll()) != null) dispatch(a);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                plugin.getLogger().warning("[Discord] Failed to start: " + e.getMessage());
            }
        }, "KoalaGuard-Discord");
        t.setDaemon(true);
        t.start();
    }

    public void sendAlert(Alert alert) {
        if (alert == null) return;
        if (jda == null || jda.getStatus() != JDA.Status.CONNECTED) {
            if (pending.size() < 200) pending.offer(alert);
            return;
        }
        dispatch(alert);
    }

    private int color(Alert a) {
        return switch (a.tier()) {
            case "PUNISH" -> 0xB71C1C;
            case "SEVERE" -> 0xE53935;
            case "HIGH"   -> 0xFB8C00;
            default        -> 0xFDD835;
        };
    }

    private void dispatch(Alert a) {
        if (alertChannelId == null || alertChannelId.isBlank()) return;
        try {
            TextChannel channel = jda.getTextChannelById(alertChannelId);
            if (channel == null) {
                plugin.getLogger().warning("[Discord] Channel " + alertChannelId + " not found.");
                return;
            }
            String emoji = a.punishment() ? "⛔" : (a.severity() >= 0.75 ? "🔴"
                    : a.severity() >= 0.40 ? "🟠" : "🟡");

            EmbedBuilder eb = new EmbedBuilder();
            eb.setColor(color(a));
            eb.setTitle(emoji + "  " + (a.punishment() ? "Punishment" : "Detection") + " — " + a.check());
            eb.setThumbnail("https://crafatar.com/avatars/" + a.uuid() + "?size=64&overlay");
            eb.addField("Player", "`" + a.player() + "`", true);
            eb.addField("Check", a.check() + " *(" + a.category() + ")*", true);
            eb.addField("Violations", a.vl() + " / " + a.maxVl(), true);
            eb.addField("Latency", a.ping() < 0 ? "n/a" : a.ping() + " ms", true);
            eb.addField("Server TPS", String.format("%.2f", a.tps()), true);
            eb.addField("Client", "`" + a.brand() + "`", true);
            eb.addField("World", a.world(), true);
            eb.addField("Location", "`" + a.coords() + "`", true);
            if (a.punishment()) {
                eb.addField("Action", "**" + a.punishmentType().toUpperCase() + "**", true);
            }
            if (a.detail() != null && !a.detail().isBlank()) {
                eb.addField("Detail", "```" + truncate(a.detail(), 200) + "```", false);
            }
            eb.setFooter("KoalaGuard • " + a.description());
            eb.setTimestamp(Instant.ofEpochMilli(a.timestamp()));

            MessageEmbed embed = eb.build();
            channel.sendMessageEmbeds(embed).queue(null,
                    err -> plugin.getLogger().warning("[Discord] Send failed: " + err.getMessage()));
        } catch (Exception e) {
            plugin.getLogger().warning("[Discord] Error: " + e.getMessage());
        }
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    public boolean isConnected() {
        return jda != null && jda.getStatus() == JDA.Status.CONNECTED;
    }

    public void reloadConfig() {
        alertChannelId = plugin.getConfig().getString("discord.alert-channel-id", "");
    }

    public void shutdown() {
        if (jda != null) {
            try { jda.shutdown(); } catch (Exception ignored) {}
        }
    }
}
