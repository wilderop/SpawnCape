package com.lawlessmc.spawncape.manager;

import com.lawlessmc.spawncape.SpawnCapePlugin;
import net.kyori.adventure.text.Component;
import org.bukkit.EntityEffect;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.scheduler.BukkitTask;

import java.util.UUID;

public final class CapeManager {

    private final SpawnCapePlugin plugin;
    private final CapeDataStore store;
    private BukkitTask tickTask;
    private BukkitTask broadcastTask;
    private UUID holderId;
    private long wearStartedMillis;
    private UUID groundItemId;
    private boolean dropQueued;
    private long lastBoostMillis;

    public CapeManager(SpawnCapePlugin plugin) {
        this.plugin = plugin;
        this.store = new CapeDataStore(plugin);
    }

    public void start() {
        store.load();
        holderId = store.holder();
        wearStartedMillis = store.wearStartedMillis();
        groundItemId = store.groundItemId();

        plugin.getServer().getScheduler().runTaskLater(plugin, this::recoverState, 20L);
        tickTask = plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, 20L, 20L);
        broadcastTask = plugin.getServer().getScheduler().runTaskTimer(
                plugin,
                this::broadcastIfHeld,
                plugin.config().broadcastIntervalTicks(),
                plugin.config().broadcastIntervalTicks()
        );
    }

    public void shutdown() {
        if (holderId != null) {
            Player holder = plugin.getServer().getPlayer(holderId);
            if (holder != null) {
                returnCape("plugin disable");
            } else {
                store.saveHolder(holderId, wearStartedMillis);
            }
        } else {
            store.saveHolder(null, 0L);
        }
        store.setGroundItemId(groundItemId);
        store.saveNow();
        if (tickTask != null) {
            tickTask.cancel();
        }
        if (broadcastTask != null) {
            broadcastTask.cancel();
        }
    }

    public CapeDataStore store() {
        return store;
    }

    public UUID holderId() {
        return holderId;
    }

    public Player holder() {
        return holderId == null ? null : plugin.getServer().getPlayer(holderId);
    }

    public boolean isHolder(Player player) {
        return holderId != null && holderId.equals(player.getUniqueId());
    }

    public boolean isMuted(UUID uuid) {
        return store.isMuted(uuid);
    }

    public void setMuted(UUID uuid, boolean muted) {
        store.setMuted(uuid, muted);
    }

    public String longestLoreText() {
        CapeDataStore.WearRecord record = store.longest();
        if (record == null || record.seconds() <= 0) {
            return "No one has worn me yet.";
        }
        return formatDuration(record.seconds()) + " " + record.name() + " has worn me the longest";
    }

    public Location currentLocation() {
        Player holder = holder();
        if (holder != null) {
            return holder.getLocation();
        }
        return plugin.config().returnLocation();
    }

    public boolean tryClaim(Player player) {
        if (holderId != null && !holderId.equals(player.getUniqueId())) {
            return false;
        }
        if (!canReceive(player)) {
            player.sendMessage(plugin.config().message("cannot-pickup"));
            return false;
        }
        ItemStack existing = findCapeInInventory(player);
        ItemStack cape = existing != null ? existing : plugin.capeItem().create();
        plugin.capeItem().refreshLore(cape);
        forceIntoOffhand(player, cape);
        beginHold(player);
        player.sendMessage(plugin.config().message("received"));
        return true;
    }

    public void beginHold(Player player) {
        clearGroundTracking();
        holderId = player.getUniqueId();
        wearStartedMillis = System.currentTimeMillis();
        store.saveHolder(holderId, wearStartedMillis);
        applyGlide(player);
    }

    public void returnCape(String reason) {
        Player previous = holder();
        UUID previousId = holderId;
        String previousName = previous != null ? previous.getName() : store.lastKnownName(previousId);

        if (previousId != null) {
            finishWear(previousId, previousName);
        }

        if (previous != null) {
            removeFromInventory(previous);
            previous.setGliding(false);
        }

        holderId = null;
        wearStartedMillis = 0L;
        store.saveHolder(null, 0L);
        removeTrackedGroundItem();
        dropAtSpawn();
        plugin.getLogger().info("Spawn Cape returned to spawn (" + reason + ").");
    }

    public boolean tryTransferTo(Player killer) {
        if (killer == null || !canReceive(killer)) {
            returnCape("death, killer could not receive");
            return false;
        }
        Player previous = holder();
        UUID previousId = holderId;
        String previousName = previous != null ? previous.getName() : store.lastKnownName(previousId);
        if (previousId != null) {
            finishWear(previousId, previousName);
        }
        if (previous != null) {
            removeFromInventory(previous);
            previous.setGliding(false);
        }
        clearGroundTracking();
        ItemStack cape = plugin.capeItem().create();
        plugin.capeItem().refreshLore(cape);
        forceIntoOffhand(killer, cape);
        beginHold(killer);
        killer.sendMessage(plugin.config().message("received"));
        return true;
    }

    public void activateTotem(Player player, Player killer) {
        if (!isHolder(player)) {
            return;
        }
        applyTotemEffects(player);
        player.sendMessage(plugin.config().message("totem"));
        if (killer != null && !killer.getUniqueId().equals(player.getUniqueId())) {
            tryTransferTo(killer);
        } else {
            returnCape("totem");
        }
    }

    private void applyTotemEffects(Player player) {
        var maxHealth = player.getAttribute(Attribute.MAX_HEALTH);
        double cap = maxHealth == null ? 20.0 : maxHealth.getValue();
        player.setHealth(Math.min(1.0, cap));
        player.setAbsorptionAmount(4.0);
        player.setFireTicks(0);
        player.setFallDistance(0.0f);
        player.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION, 45 * 20, 1));
        player.addPotionEffect(new PotionEffect(PotionEffectType.ABSORPTION, 5 * 20, 1));
        player.addPotionEffect(new PotionEffect(PotionEffectType.FIRE_RESISTANCE, 40 * 20, 0));
        player.playEffect(EntityEffect.TOTEM_RESURRECT);
        player.getWorld().playSound(player.getLocation(), Sound.ITEM_TOTEM_USE, 1.0f, 1.0f);
    }

    public boolean canReceive(Player player) {
        PlayerInventory inventory = player.getInventory();
        ItemStack offhand = inventory.getItemInOffHand();
        if (offhand.getType().isAir() || plugin.capeItem().isCape(offhand)) {
            return true;
        }
        return inventory.firstEmpty() != -1;
    }

    public void forceIntoOffhand(Player player, ItemStack cape) {
        PlayerInventory inventory = player.getInventory();
        ItemStack offhand = inventory.getItemInOffHand();
        if (!offhand.getType().isAir() && !plugin.capeItem().isCape(offhand)) {
            int empty = inventory.firstEmpty();
            if (empty != -1) {
                inventory.setItem(empty, offhand);
            }
        }
        inventory.setItemInOffHand(cape);
    }

    public void enforceOffhand(Player player) {
        PlayerInventory inventory = player.getInventory();
        ItemStack offhand = inventory.getItemInOffHand();
        if (plugin.capeItem().isCape(offhand)) {
            return;
        }
        ItemStack found = findCapeInInventory(player);
        if (found != null) {
            forceIntoOffhand(player, found);
            return;
        }
        ItemStack cape = plugin.capeItem().create();
        plugin.capeItem().refreshLore(cape);
        if (canReceive(player)) {
            forceIntoOffhand(player, cape);
        } else {
            returnCape("holder had no space");
        }
    }

    public boolean isOutOfBounds(Location location) {
        if (location.getWorld() == null) {
            return true;
        }
        String name = location.getWorld().getName();
        double x = location.getX();
        double y = location.getY();
        double z = location.getZ();

        if (name.equals(plugin.config().overworldName())) {
            return exceeds(x, y, z, plugin.config().overworldLimit());
        }
        if (name.equals(plugin.config().netherName())) {
            return exceeds(x, y, z, plugin.config().netherLimit());
        }
        return true;
    }

    public void boost(Player player) {
        long now = System.currentTimeMillis();
        if (now - lastBoostMillis < 150L) {
            return;
        }
        lastBoostMillis = now;

        if (!player.isGliding()) {
            player.setGliding(true);
        }
        ItemStack rocket = new ItemStack(org.bukkit.Material.FIREWORK_ROCKET);
        try {
            player.fireworkBoost(rocket);
        } catch (IllegalArgumentException | IllegalStateException ignored) {
            player.setVelocity(player.getLocation().getDirection().multiply(1.5)
                    .add(new org.bukkit.util.Vector(0, 0.3, 0)));
        }
    }

    public void broadcastIfHeld() {
        Player holder = holder();
        if (holder == null) {
            return;
        }
        Location loc = holder.getLocation();
        Component message = plugin.config().message(
                "broadcast",
                plugin.config().locationResolvers(holder.getName(), loc)
        );
        String plain = plugin.config().plainBroadcast(
                holder.getName(),
                loc.getBlockX(),
                loc.getBlockY(),
                loc.getBlockZ(),
                loc.getWorld() == null ? "unknown" : loc.getWorld().getName()
        );
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            if (!isMuted(player.getUniqueId())) {
                player.sendMessage(message);
            }
        }
        plugin.getServer().getConsoleSender().sendMessage(message);
        plugin.discord().send(plain);
    }

    public void sendStatus(org.bukkit.command.CommandSender viewer) {
        Player holder = holder();
        if (holder != null) {
            viewer.sendMessage(plugin.config().message(
                    "location-held",
                    plugin.config().locationResolvers(holder.getName(), holder.getLocation())
            ));
            return;
        }
        Location spawn = plugin.config().returnLocation();
        viewer.sendMessage(plugin.config().message(
                "location-ground",
                plugin.config().locationResolvers("Spawn Cape", spawn)
        ));
    }

    public void ensureSpawnItem() {
        if (holderId != null) {
            return;
        }
        if (findTrackedGroundItem() != null) {
            return;
        }
        dropAtSpawn();
    }

    private void tick() {
        Player holder = holder();
        if (holder != null) {
            enforceOffhand(holder);
            applyGlide(holder);
            if (isOutOfBounds(holder.getLocation())) {
                returnCape("out of bounds");
            }
            return;
        }

        if (holderId != null) {
            returnCape("holder offline");
            return;
        }

        Location spawn = plugin.config().returnLocation();
        World world = spawn.getWorld();
        if (world == null) {
            return;
        }
        if (!world.isChunkLoaded(plugin.config().returnChunkX(), plugin.config().returnChunkZ())) {
            return;
        }
        if (findTrackedGroundItem() == null) {
            dropAtSpawn();
        }
    }

    private void recoverState() {
        Player onlineHolder = findOnlineHolder();
        if (onlineHolder != null) {
            holderId = onlineHolder.getUniqueId();
            if (wearStartedMillis <= 0L) {
                wearStartedMillis = System.currentTimeMillis();
            }
            clearGroundTracking();
            enforceOffhand(onlineHolder);
            store.saveHolder(holderId, wearStartedMillis);
            return;
        }

        holderId = null;
        wearStartedMillis = 0L;
        store.saveHolder(null, 0L);

        removeCapeItemsInSpawnChunk();
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            removeFromInventory(player);
        }
        dropAtSpawn();
    }

    private Player findOnlineHolder() {
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            if (findCapeInInventory(player) != null) {
                return player;
            }
        }
        return null;
    }

    private void applyGlide(Player player) {
        if (!player.isOnGround() && !player.isInWater() && !player.isGliding()) {
            player.setGliding(true);
        }
    }

    private void finishWear(UUID uuid, String name) {
        if (uuid == null || wearStartedMillis <= 0L) {
            return;
        }
        long seconds = Math.max(0L, (System.currentTimeMillis() - wearStartedMillis) / 1000L);
        store.addWearTime(uuid, name == null ? "Unknown" : name, seconds);
        wearStartedMillis = 0L;
    }

    private void dropAtSpawn() {
        if (holderId != null || dropQueued) {
            return;
        }
        if (findTrackedGroundItem() != null) {
            return;
        }

        Location location = plugin.config().returnLocation();
        World world = location.getWorld();
        if (world == null) {
            plugin.getLogger().warning("Return world is not loaded; cannot drop Spawn Cape.");
            return;
        }

        dropQueued = true;
        world.getChunkAtAsync(location).thenAccept(chunk -> plugin.getServer().getScheduler().runTask(plugin, () -> {
            dropQueued = false;
            if (holderId != null || findTrackedGroundItem() != null) {
                return;
            }
            removeCapeItemsInSpawnChunk();
            ItemStack stack = plugin.capeItem().create();
            plugin.capeItem().refreshLore(stack);
            Item dropped = world.dropItem(location, stack);
            styleGroundItem(dropped);
            groundItemId = dropped.getUniqueId();
            store.setGroundItemId(groundItemId);
        }));
    }

    public void styleGroundItem(Item item) {
        item.setUnlimitedLifetime(true);
        item.setInvulnerable(true);
        item.setGlowing(true);
        item.setCanMobPickup(false);
        item.setPickupDelay(0);
        item.customName(plugin.config().itemName());
        item.setCustomNameVisible(true);
        item.setPersistent(true);
    }

    private Item findTrackedGroundItem() {
        if (groundItemId == null) {
            return null;
        }
        for (World world : plugin.getServer().getWorlds()) {
            Entity entity = world.getEntity(groundItemId);
            if (entity instanceof Item item && plugin.capeItem().isCape(item.getItemStack()) && !item.isDead()) {
                return item;
            }
        }
        groundItemId = null;
        store.setGroundItemId(null);
        return null;
    }

    private void removeTrackedGroundItem() {
        Item item = findTrackedGroundItem();
        if (item != null) {
            item.remove();
        }
        clearGroundTracking();
    }

    private void clearGroundTracking() {
        groundItemId = null;
        store.setGroundItemId(null);
    }

    private void removeCapeItemsInSpawnChunk() {
        Location location = plugin.config().returnLocation();
        World world = location.getWorld();
        if (world == null) {
            return;
        }
        int chunkX = plugin.config().returnChunkX();
        int chunkZ = plugin.config().returnChunkZ();
        if (!world.isChunkLoaded(chunkX, chunkZ)) {
            return;
        }
        for (Entity entity : world.getChunkAt(chunkX, chunkZ).getEntities()) {
            if (entity instanceof Item item && plugin.capeItem().isCape(item.getItemStack())) {
                item.remove();
            }
        }
        groundItemId = null;
        store.setGroundItemId(null);
    }

    public ItemStack findCapeInInventory(Player player) {
        PlayerInventory inventory = player.getInventory();
        if (plugin.capeItem().isCape(inventory.getItemInOffHand())) {
            return inventory.getItemInOffHand();
        }
        for (ItemStack stack : inventory.getStorageContents()) {
            if (plugin.capeItem().isCape(stack)) {
                return stack;
            }
        }
        return null;
    }

    public void removeFromInventory(Player player) {
        PlayerInventory inventory = player.getInventory();
        if (plugin.capeItem().isCape(inventory.getItemInOffHand())) {
            inventory.setItemInOffHand(null);
        }
        ItemStack[] contents = inventory.getStorageContents();
        for (int i = 0; i < contents.length; i++) {
            if (plugin.capeItem().isCape(contents[i])) {
                inventory.setItem(i, null);
            }
        }
        ItemStack cursor = player.getItemOnCursor();
        if (plugin.capeItem().isCape(cursor)) {
            player.setItemOnCursor(null);
        }
    }

    public static String formatDuration(long totalSeconds) {
        long seconds = Math.max(0L, totalSeconds);
        long days = seconds / 86400;
        seconds %= 86400;
        long hours = seconds / 3600;
        seconds %= 3600;
        long minutes = seconds / 60;
        seconds %= 60;
        if (days > 0) {
            return days + "d " + hours + "h";
        }
        if (hours > 0) {
            return hours + "h " + minutes + "m";
        }
        if (minutes > 0) {
            return minutes + "m";
        }
        return seconds + "s";
    }

    private static boolean exceeds(double x, double y, double z, double limit) {
        return Math.abs(x) > limit || Math.abs(y) > limit || Math.abs(z) > limit;
    }
}
