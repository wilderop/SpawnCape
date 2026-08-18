package com.lawlessmc.spawncape.manager;

import com.lawlessmc.spawncape.SpawnCapePlugin;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

public final class DiscordWebhook {

    private final SpawnCapePlugin plugin;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    public DiscordWebhook(SpawnCapePlugin plugin) {
        this.plugin = plugin;
    }

    public void send(String content) {
        String url = plugin.config().discordWebhook();
        if (url == null || url.isBlank()) {
            return;
        }
        String escaped = content
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n");
        String body = "{\"content\":\"" + escaped + "\"}";
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(8))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        httpClient.sendAsync(request, HttpResponse.BodyHandlers.discarding())
                .exceptionally(ex -> {
                    plugin.getLogger().warning("Discord webhook failed: " + ex.getMessage());
                    return null;
                });
    }
}
