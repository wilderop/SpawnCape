package com.lawlessmc.spawncape;

import com.lawlessmc.spawncape.command.CapeCommand;
import com.lawlessmc.spawncape.item.CapeItem;
import com.lawlessmc.spawncape.listener.CapeListener;
import com.lawlessmc.spawncape.manager.CapeManager;
import com.lawlessmc.spawncape.manager.ConfigManager;
import com.lawlessmc.spawncape.manager.DiscordWebhook;
import org.bukkit.plugin.java.JavaPlugin;

public final class SpawnCapePlugin extends JavaPlugin {

    private ConfigManager configManager;
    private CapeItem capeItem;
    private CapeManager capeManager;
    private DiscordWebhook discordWebhook;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        this.configManager = new ConfigManager(this);
        this.capeItem = new CapeItem(this);
        this.discordWebhook = new DiscordWebhook(this);
        this.capeManager = new CapeManager(this);

        getServer().getPluginManager().registerEvents(new CapeListener(this), this);
        var capeCommand = new CapeCommand(this);
        var command = getCommand("cape");
        if (command != null) {
            command.setExecutor(capeCommand);
            command.setTabCompleter(capeCommand);
        }

        capeManager.start();
        getLogger().info("Spawn Cape enabled.");
    }

    @Override
    public void onDisable() {
        if (capeManager != null) {
            capeManager.shutdown();
        }
        getLogger().info("Spawn Cape disabled.");
    }

    public ConfigManager config() {
        return configManager;
    }

    public CapeItem capeItem() {
        return capeItem;
    }

    public CapeManager capeManager() {
        return capeManager;
    }

    public DiscordWebhook discord() {
        return discordWebhook;
    }
}
