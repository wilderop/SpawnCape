package com.lawlessmc.spawncape.manager;

import com.lawlessmc.spawncape.SpawnCapePlugin;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
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

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

public final class CapeManager {

    public record Milestone(long seconds, String label) {}

    private static final DateTimeFormatter KEEPSAKE_DATE =
            DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.ENGLISH);
    private static final long WEEK_SECONDS = 7L * 24L * 60L * 60L;
    private static final long YEAR_SECONDS = 52L * WEEK_SECONDS;
    private static final List<Milestone> MILESTONES = buildMilestones();

    private final SpawnCapePlugin plugin;
    private final CapeDataStore store;
    private BukkitTask tickTask;
    private BukkitTask glideTask;
    private BukkitTask broadcastTask;
    private BukkitTask graceTask;
    private UUID holderId;
    private long wearStartedMillis;
    private UUID groundItemId;
    private boolean dropQueued;
    private long lastBoostMillis;
    private long holdRewardPayouts;

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
        glideTask = plugin.getServer().getScheduler().runTaskTimer(plugin, this::tickGlide, 1L, 1L);
        broadcastTask = plugin.getServer().getScheduler().runTaskTimer(
                plugin,
                this::broadcastIfHeld,
                plugin.config().broadcastIntervalTicks(),
                plugin.config().broadcastIntervalTicks()
        );
    }

    public void shutdown() {
        if (graceTask != null) {
            graceTask.cancel();
            graceTask = null;
        }
        store.saveHolder(holderId, wearStartedMillis);
        store.setGroundItemId(groundItemId);
        store.saveNow();
        if (tickTask != null) {
            tickTask.cancel();
        }
        if (glideTask != null) {
            glideTask.cancel();
        }
        if (broadcastTask != null) {
            broadcastTask.cancel();
        }
    }

    public void saveForReboot() {
        store.saveHolder(holderId, wearStartedMillis);
        store.setGroundItemId(groundItemId);
        store.saveNow();
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
        holdRewardPayouts = 0L;
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
        holdRewardPayouts = 0L;
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
            awardMilestones(holder);
            awardHoldReward(holder);
            stripCapeFromEnderChest(holder);
            if (isOutOfBounds(holder.getLocation())) {
                returnCape("out of bounds");
            }
            return;
        }
        if (holderId != null) {
            if (graceTask != null) {
                return;
            }
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
        UUID saved = store.holder();
        Player onlineHolder = saved != null ? plugin.getServer().getPlayer(saved) : findOnlineHolder();
        if (onlineHolder == null) {
            onlineHolder = findOnlineHolder();
        }
        if (onlineHolder != null) {
            restoreSession(onlineHolder, false);
            return;
        }
        if (saved != null && plugin.config().rebootGraceSeconds() > 0L) {
            holderId = saved;
            startGracePeriod();
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

    public boolean tryRestoreAfterReboot(Player player) {
        UUID saved = holderId != null ? holderId : store.holder();
        if (saved == null || !saved.equals(player.getUniqueId())) {
            return false;
        }
        restoreSession(player, true);
        return true;
    }

    private void restoreSession(Player player, boolean announce) {
        if (graceTask != null) {
            graceTask.cancel();
            graceTask = null;
        }
        holderId = player.getUniqueId();
        if (wearStartedMillis <= 0L) {
            wearStartedMillis = store.wearStartedMillis();
        }
        if (wearStartedMillis <= 0L) {
            wearStartedMillis = System.currentTimeMillis();
        }
        clearGroundTracking();
        enforceOffhand(player);
        store.saveHolder(holderId, wearStartedMillis);
        long interval = plugin.config().holdRewardIntervalSeconds();
        if (interval > 0L && wearStartedMillis > 0L) {
            long elapsed = Math.max(0L, (System.currentTimeMillis() - wearStartedMillis) / 1000L);
            holdRewardPayouts = elapsed / interval;
        }
        awardMilestones(player);
        if (announce) {
            player.sendMessage(plugin.config().message("grace-restored"));
        }
    }

    private void startGracePeriod() {
        if (graceTask != null) {
            graceTask.cancel();
        }
        long ticks = Math.max(20L, plugin.config().rebootGraceSeconds() * 20L);
        plugin.getLogger().info("Waiting " + plugin.config().rebootGraceSeconds()
                + "s for the Spawn Cape holder to rejoin.");
        graceTask = plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            graceTask = null;
            if (holder() != null) {
                return;
            }
            returnCape("reboot grace expired");
        }, ticks);
    }

    private Player findOnlineHolder() {
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            if (findCapeInInventory(player) != null) {
                return player;
            }
        }
        return null;
    }

    public boolean shouldKeepGliding(Player player) {
        return !player.isOnGround() && !player.isInWater() && !player.isSwimming();
    }

    private void applyGlide(Player player) {
        if (shouldKeepGliding(player) && !player.isGliding()) {
            player.setGliding(true);
        }
    }

    private void tickGlide() {
        Player holder = holder();
        if (holder != null) {
            applyGlide(holder);
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
        stripCapeFromEnderChest(player);
    }

    public void stripCapeFromEnderChest(Player player) {
        if (player == null) {
            return;
        }
        var ender = player.getEnderChest();
        boolean changed = false;
        for (int i = 0; i < ender.getSize(); i++) {
            if (plugin.capeItem().isCape(ender.getItem(i))) {
                ender.setItem(i, null);
                changed = true;
            }
        }
        if (changed) {
            player.updateInventory();
        }
    }

    public void awardHoldReward(Player player) {
        if (player == null || wearStartedMillis <= 0L) {
            return;
        }
        long interval = plugin.config().holdRewardIntervalSeconds();
        int amount = plugin.config().holdRewardAmount();
        if (interval <= 0L || amount <= 0) {
            return;
        }
        long elapsed = Math.max(0L, (System.currentTimeMillis() - wearStartedMillis) / 1000L);
        long due = elapsed / interval;
        if (due <= holdRewardPayouts) {
            return;
        }
        long times = due - holdRewardPayouts;
        holdRewardPayouts = due;
        int total = (int) Math.min(times * (long) amount, 64L * 27L);
        ItemStack stack = new ItemStack(plugin.config().holdRewardMaterial(), total);
        var leftover = player.getInventory().addItem(stack);
        for (ItemStack extra : leftover.values()) {
            if (extra == null || extra.getType().isAir()) {
                continue;
            }
            player.getWorld().dropItemNaturally(player.getLocation(), extra);
        }
    }

    public void awardMilestones(Player player) {
        if (player == null || wearStartedMillis <= 0L) {
            return;
        }
        long elapsed = Math.max(0L, (System.currentTimeMillis() - wearStartedMillis) / 1000L);
        for (Milestone milestone : MILESTONES) {
            tryAward(player, elapsed, milestone);
        }
        if (elapsed >= YEAR_SECONDS * 2L) {
            long maxYear = elapsed / YEAR_SECONDS;
            for (long year = 2L; year <= maxYear; year++) {
                tryAward(player, elapsed, new Milestone(year * YEAR_SECONDS, year + " years"));
            }
        }
    }

    private void tryAward(Player player, long elapsed, Milestone milestone) {
        if (elapsed < milestone.seconds()) {
            return;
        }
        if (store.hasAwarded(player.getUniqueId(), milestone.seconds())) {
            return;
        }
        store.markAwarded(player.getUniqueId(), milestone.seconds());
        giveKeepsake(player, milestone.label());
    }

    private void giveKeepsake(Player player, String durationLabel) {
        String date = LocalDate.now().format(KEEPSAKE_DATE).toUpperCase(Locale.ENGLISH);
        ItemStack keepsake = plugin.capeItem().createKeepsake(player.getName(), durationLabel, date);
        PlayerInventory inventory = player.getInventory();
        int empty = inventory.firstEmpty();
        if (empty != -1) {
            inventory.setItem(empty, keepsake);
        } else {
            Item dropped = player.getWorld().dropItemNaturally(player.getLocation(), keepsake);
            styleKeepsakeItem(dropped);
        }
        player.sendMessage(plugin.config().message(
                "keepsake",
                Placeholder.unparsed("duration", durationLabel)
        ));
    }

    public void styleKeepsakeItem(Item item) {
        item.setUnlimitedLifetime(true);
        item.setInvulnerable(true);
        item.setPersistent(true);
        item.setCanMobPickup(false);
        item.setPickupDelay(0);
    }

    private static List<Milestone> buildMilestones() {
        List<Milestone> list = new ArrayList<>();
        list.add(new Milestone(60L, "1 minute"));
        list.add(new Milestone(3600L, "1 hour"));
        list.add(new Milestone(6L * 3600L, "6 hours"));
        list.add(new Milestone(12L * 3600L, "12 hours"));
        list.add(new Milestone(24L * 3600L, "24 hours"));
        list.add(new Milestone(WEEK_SECONDS, "1 week"));
        for (int week = 2; week <= 51; week++) {
            list.add(new Milestone(week * WEEK_SECONDS, week + " weeks"));
        }
        list.add(new Milestone(YEAR_SECONDS, "1 year"));
        return List.copyOf(list);
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
