package com.koalaguard.discord;

import com.koalaguard.KoalaGuard;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.requests.GatewayIntent;
import net.dv8tion.jda.api.utils.cache.CacheFlag;

import java.util.EnumSet;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Lightweight JDA bot that forwards KoalaGuard alerts to a Discord channel.
 *
 * Setup:
 *   1. Create a bot at https://discord.com/developers/applications
 *   2. Enable "Message Content Intent" in the Bot settings
 *   3. Invite the bot to your server with "Send Messages" permission
 *   4. In config.yml set discord.bot-token and discord.alert-channel-id
 */
public final class DiscordBot {

    private final KoalaGuard plugin;
    private volatile JDA jda;
    private volatile String alertChannelId;

    // Buffer messages that arrive before JDA is ready
    private final Queue<String> pendingMessages = new ConcurrentLinkedQueue<>();

    public DiscordBot(KoalaGuard plugin) {
        this.plugin = plugin;
    }

    public void start() {
        String token = plugin.getConfig().getString("discord.bot-token", "");
        alertChannelId = plugin.getConfig().getString("discord.alert-channel-id", "");

        if (token == null || token.isBlank()) {
            plugin.getLogger().info("[Discord] discord.bot-token not set — bot disabled.");
            return;
        }
        if (alertChannelId == null || alertChannelId.isBlank()) {
            plugin.getLogger().warning("[Discord] discord.alert-channel-id not set — alerts won't be sent.");
        }

        Thread botThread = new Thread(() -> {
            try {
                jda = JDABuilder
                        .createLight(token, EnumSet.noneOf(GatewayIntent.class))
                        .disableCache(EnumSet.allOf(CacheFlag.class))
                        .build()
                        .awaitReady();

                plugin.getLogger().info("[Discord] Bot connected as: " + jda.getSelfUser().getAsTag());

                // Drain any messages buffered before the bot was ready
                String buffered;
                while ((buffered = pendingMessages.poll()) != null) {
                    dispatchToChannel(buffered);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                plugin.getLogger().warning("[Discord] Failed to start bot: " + e.getMessage());
            }
        }, "KoalaGuard-Discord");
        botThread.setDaemon(true);
        botThread.start();
    }

    /** Send an alert message. Safe to call from any thread. */
    public void sendAlert(String message) {
        if (message == null || message.isBlank()) return;
        String safe = message.length() > 1990 ? message.substring(0, 1990) + "..." : message;
        if (jda == null || jda.getStatus() != JDA.Status.CONNECTED) {
            pendingMessages.offer(safe);
            return;
        }
        dispatchToChannel(safe);
    }

    private void dispatchToChannel(String message) {
        if (alertChannelId == null || alertChannelId.isBlank()) return;
        try {
            TextChannel channel = jda.getTextChannelById(alertChannelId);
            if (channel == null) {
                plugin.getLogger().warning("[Discord] Channel ID " + alertChannelId + " not found. Check discord.alert-channel-id.");
                return;
            }
            channel.sendMessage(message).queue(
                    null,
                    err -> plugin.getLogger().warning("[Discord] Failed to send message: " + err.getMessage())
            );
        } catch (Exception e) {
            plugin.getLogger().warning("[Discord] Send error: " + e.getMessage());
        }
    }

    public void shutdown() {
        if (jda != null) {
            try {
                jda.shutdown();
            } catch (Exception ignored) {}
        }
    }

    public boolean isConnected() {
        return jda != null && jda.getStatus() == JDA.Status.CONNECTED;
    }

    /** Reload channel ID from config (call after /kg reload). */
    public void reloadConfig() {
        alertChannelId = plugin.getConfig().getString("discord.alert-channel-id", "");
    }
}
