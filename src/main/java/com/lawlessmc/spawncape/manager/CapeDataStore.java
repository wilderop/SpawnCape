package com.lawlessmc.spawncape.manager;

import com.lawlessmc.spawncape.SpawnCapePlugin;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

public final class CapeDataStore {

    public record WearRecord(UUID uuid, String name, long seconds) {}

    private final SpawnCapePlugin plugin;
    private final File file;
    private YamlConfiguration yaml;
    private final Set<UUID> muted = new HashSet<>();
    private final AtomicBoolean saveQueued = new AtomicBoolean(false);

    private UUID groundItemId;
    private WearRecord cachedLongest;

    public CapeDataStore(SpawnCapePlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "cape.yml");
    }

    public void load() {
        if (!plugin.getDataFolder().exists()) {
            plugin.getDataFolder().mkdirs();
        }
        yaml = YamlConfiguration.loadConfiguration(file);
        muted.clear();
        for (String raw : yaml.getStringList("muted")) {
            try {
                muted.add(UUID.fromString(raw));
            } catch (IllegalArgumentException ignored) {
            }
        }
        groundItemId = parseUuid(yaml.getString("ground-item"));
        cachedLongest = readLongestFromYaml();
    }

    public UUID holder() {
        return parseUuid(yaml.getString("holder"));
    }

    public long wearStartedMillis() {
        return yaml.getLong("wear-started", 0L);
    }

    public UUID groundItemId() {
        return groundItemId;
    }

    public void setGroundItemId(UUID id) {
        groundItemId = id;
        yaml.set("ground-item", id == null ? null : id.toString());
        queueSave();
    }

    public void saveHolder(UUID holder, long wearStartedMillis) {
        yaml.set("holder", holder == null ? null : holder.toString());
        yaml.set("wear-started", holder == null ? 0L : wearStartedMillis);
        queueSave();
    }

    public String lastKnownName(UUID uuid) {
        if (uuid == null) {
            return "Unknown";
        }
        return yaml.getString("wear." + uuid + ".name", "Unknown");
    }

    public void addWearTime(UUID uuid, String name, long extraSeconds) {
        if (uuid == null || extraSeconds <= 0L) {
            return;
        }
        String path = "wear." + uuid;
        long current = yaml.getLong(path + ".seconds", 0L);
        long total = current + extraSeconds;
        yaml.set(path + ".seconds", total);
        yaml.set(path + ".name", name);
        if (cachedLongest == null || total > cachedLongest.seconds()) {
            cachedLongest = new WearRecord(uuid, name, total);
            yaml.set("longest.uuid", uuid.toString());
            yaml.set("longest.name", name);
            yaml.set("longest.seconds", total);
        }
        queueSave();
    }

    public WearRecord longest() {
        return cachedLongest;
    }

    public boolean isMuted(UUID uuid) {
        return muted.contains(uuid);
    }

    public void setMuted(UUID uuid, boolean value) {
        if (value) {
            muted.add(uuid);
        } else {
            muted.remove(uuid);
        }
        yaml.set("muted", muted.stream().map(UUID::toString).toList());
        queueSave();
    }

    public Set<UUID> muted() {
        return muted;
    }

    public void saveNow() {
        saveQueued.set(false);
        try {
            yaml.save(file);
        } catch (IOException ex) {
            plugin.getLogger().severe("Could not save cape.yml: " + ex.getMessage());
        }
    }

    private void queueSave() {
        if (!saveQueued.compareAndSet(false, true)) {
            return;
        }
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            final String data = yaml.saveToString();
            plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
                try {
                    Files.writeString(file.toPath(), data, StandardCharsets.UTF_8);
                } catch (IOException ex) {
                    plugin.getLogger().severe("Could not save cape.yml: " + ex.getMessage());
                } finally {
                    saveQueued.set(false);
                }
            });
        });
    }

    private WearRecord readLongestFromYaml() {
        String raw = yaml.getString("longest.uuid");
        if (raw != null) {
            try {
                UUID uuid = UUID.fromString(raw);
                return new WearRecord(
                        uuid,
                        yaml.getString("longest.name", "Unknown"),
                        yaml.getLong("longest.seconds", 0L)
                );
            } catch (IllegalArgumentException ignored) {
            }
        }
        var section = yaml.getConfigurationSection("wear");
        if (section == null) {
            return null;
        }
        WearRecord best = null;
        for (String key : section.getKeys(false)) {
            try {
                UUID uuid = UUID.fromString(key);
                long seconds = yaml.getLong("wear." + key + ".seconds", 0L);
                String name = yaml.getString("wear." + key + ".name", "Unknown");
                if (best == null || seconds > best.seconds()) {
                    best = new WearRecord(uuid, name, seconds);
                }
            } catch (IllegalArgumentException ignored) {
            }
        }
        return best;
    }

    private static UUID parseUuid(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
