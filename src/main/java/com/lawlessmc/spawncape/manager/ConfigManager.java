package com.lawlessmc.spawncape.manager;

import com.lawlessmc.spawncape.SpawnCapePlugin;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.List;

public final class ConfigManager {

    private final SpawnCapePlugin plugin;
    private final MiniMessage miniMessage = MiniMessage.miniMessage();

    private String overworldName;
    private String netherName;
    private String returnWorldName;
    private double returnX;
    private double returnY;
    private double returnZ;
    private double overworldLimit;
    private double netherLimit;
    private long broadcastIntervalTicks;
    private double reachBonus;
    private String discordWebhook;
    private String itemNameRaw;
    private Component itemName;
    private Material itemMaterial;
    private boolean itemGlow;
    private Component prefix;
    private String broadcastTemplate;
    private long rebootGraceSeconds;
    private Component keepsakeName;
    private long holdRewardIntervalSeconds;
    private int holdRewardAmount;
    private Material holdRewardMaterial;

    public ConfigManager(SpawnCapePlugin plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        plugin.reloadConfig();
        FileConfiguration cfg = plugin.getConfig();

        overworldName = cfg.getString("overworld-name", "world");
        netherName = cfg.getString("nether-name", "world_nether");
        returnWorldName = cfg.getString("return.world", overworldName);
        returnX = cfg.getDouble("return.x", 0.5);
        returnY = cfg.getDouble("return.y", 100.0);
        returnZ = cfg.getDouble("return.z", 0.5);
        overworldLimit = cfg.getDouble("overworld-limit", 1000.0);
        netherLimit = cfg.getDouble("nether-limit", 250.0);
        broadcastIntervalTicks = Math.max(20L, cfg.getLong("broadcast-interval-seconds", 300L) * 20L);
        reachBonus = cfg.getDouble("reach-bonus", 1.0);
        discordWebhook = cfg.getString("discord-webhook", "");
        itemNameRaw = cfg.getString("item.name", "<gold><bold>Spawn Cape");
        itemName = miniMessage.deserialize(itemNameRaw);
        String materialName = cfg.getString("item.material", "ELYTRA");
        Material matched = Material.matchMaterial(materialName);
        itemMaterial = matched == null ? Material.ELYTRA : matched;
        itemGlow = cfg.getBoolean("item.glow", true);
        prefix = miniMessage.deserialize(cfg.getString("messages.prefix", ""));
        broadcastTemplate = cfg.getString(
                "messages.broadcast",
                "<player> has the fabled Spawn Cape and is at <x>, <y>, <z> in <world>."
        );
        rebootGraceSeconds = Math.max(0L, cfg.getLong("reboot-grace-seconds", 300L));
        keepsakeName = miniMessage.deserialize(cfg.getString("keepsake-name", "<gold>Spawn Cape Keepsake"));
        holdRewardIntervalSeconds = Math.max(0L, cfg.getLong("hold-reward.interval-seconds", 60L));
        holdRewardAmount = Math.max(0, cfg.getInt("hold-reward.amount", 2));
        Material reward = Material.matchMaterial(cfg.getString("hold-reward.material", "OBSIDIAN"));
        holdRewardMaterial = reward == null ? Material.OBSIDIAN : reward;
    }

    public String overworldName() {
        return overworldName;
    }

    public String netherName() {
        return netherName;
    }

    public Location returnLocation() {
        World world = plugin.getServer().getWorld(returnWorldName);
        if (world == null) {
            world = plugin.getServer().getWorld(overworldName);
        }
        if (world == null && !plugin.getServer().getWorlds().isEmpty()) {
            world = plugin.getServer().getWorlds().getFirst();
        }
        return new Location(world, returnX, returnY, returnZ);
    }

    public int returnChunkX() {
        return Location.locToBlock(returnX) >> 4;
    }

    public int returnChunkZ() {
        return Location.locToBlock(returnZ) >> 4;
    }

    public double overworldLimit() {
        return overworldLimit;
    }

    public double netherLimit() {
        return netherLimit;
    }

    public long broadcastIntervalTicks() {
        return broadcastIntervalTicks;
    }

    public double reachBonus() {
        return reachBonus;
    }

    public String discordWebhook() {
        return discordWebhook;
    }

    public Component itemName() {
        return itemName;
    }

    public Material itemMaterial() {
        return itemMaterial;
    }

    public boolean itemGlow() {
        return itemGlow;
    }

    public Component prefix() {
        return prefix;
    }

    public long rebootGraceSeconds() {
        return rebootGraceSeconds;
    }

    public Component keepsakeName() {
        return keepsakeName;
    }

    public long holdRewardIntervalSeconds() {
        return holdRewardIntervalSeconds;
    }

    public int holdRewardAmount() {
        return holdRewardAmount;
    }

    public Material holdRewardMaterial() {
        return holdRewardMaterial;
    }

    public Component message(String path, TagResolver... resolvers) {
        String raw = plugin.getConfig().getString("messages." + path, path);
        return prefix.append(miniMessage.deserialize(raw, resolvers));
    }

    public void sendHelp(CommandSender sender) {
        List<String> lines = plugin.getConfig().getStringList("messages.help");
        if (lines.isEmpty()) {
            sender.sendMessage(message("hint"));
            return;
        }
        for (String line : lines) {
            sender.sendMessage(prefix.append(miniMessage.deserialize(line)));
        }
    }

    public String plainBroadcast(String player, int x, int y, int z, String world) {
        return broadcastTemplate
                .replace("<player>", player)
                .replace("<x>", Integer.toString(x))
                .replace("<y>", Integer.toString(y))
                .replace("<z>", Integer.toString(z))
                .replace("<world>", world)
                .replaceAll("<[^>]+>", "");
    }

    public TagResolver locationResolvers(String player, Location location) {
        return TagResolver.resolver(
                Placeholder.unparsed("player", player),
                Placeholder.unparsed("x", Integer.toString(location.getBlockX())),
                Placeholder.unparsed("y", Integer.toString(location.getBlockY())),
                Placeholder.unparsed("z", Integer.toString(location.getBlockZ())),
                Placeholder.unparsed("world", location.getWorld() == null ? "unknown" : location.getWorld().getName())
        );
    }
}
