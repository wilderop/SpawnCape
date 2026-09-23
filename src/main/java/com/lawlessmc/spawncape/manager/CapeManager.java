package com.lawlessmc.spawncape.manager;

import com.lawlessmc.spawncape.SpawnCapePlugin;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.title.Title;
import org.bukkit.Chunk;
import org.bukkit.EntityEffect;
import org.bukkit.FluidCollisionMode;
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

import java.time.Duration;
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
    private long graceUntilMillis;
    private String graceExpireReason;
    private UUID groundItemId;
    private boolean dropQueued;
    private boolean loadingGroundChunk;
    private long lastBoostMillis;
    private long holdRewardPayouts;
    private boolean boundsWarned;

    public CapeManager(SpawnCapePlugin plugin) {
        this.plugin = plugin;
        this.store = new CapeDataStore(plugin);
    }

    public void start() {
        store.load();
        holderId = store.holder();
        wearStartedMillis = store.wearStartedMillis();
        groundItemId = store.groundItemId();
        graceUntilMillis = store.graceUntilMillis();

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
        cancelGraceTask();
        long now = System.currentTimeMillis();
        if (holderId != null && holder() == null && graceUntilMillis > 0L && now >= graceUntilMillis) {
            holderId = null;
            wearStartedMillis = 0L;
            graceUntilMillis = 0L;
        } else if (holderId != null && holder() != null) {
            long reboot = plugin.config().rebootGraceSeconds();
            if (reboot > 0L && graceUntilMillis <= now) {
                graceUntilMillis = now + reboot * 1000L;
            }
        }
        store.saveHolder(holderId, wearStartedMillis);
        store.setGraceUntil(holderId == null ? 0L : graceUntilMillis);
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
        store.setGraceUntil(holderId == null ? 0L : graceUntilMillis);
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

    public long currentHoldSeconds() {
        if (wearStartedMillis <= 0L) {
            return 0L;
        }
        return Math.max(0L, (System.currentTimeMillis() - wearStartedMillis) / 1000L);
    }

    public int currentKillPrize() {
        long interval = plugin.config().killPrizeIntervalSeconds();
        int amount = plugin.config().killPrizeAmount();
        if (interval <= 0L || amount <= 0) {
            return 0;
        }
        long stacks = currentHoldSeconds() / interval;
        return (int) Math.min(stacks * (long) amount, 64L * 27L);
    }

    public void payoutKillPrize(Player killer) {
        int amount = currentKillPrize();
        if (killer == null || amount <= 0) {
            return;
        }
        ItemStack stack = new ItemStack(plugin.config().killPrizeMaterial(), amount);
        var leftover = killer.getInventory().addItem(stack);
        for (ItemStack extra : leftover.values()) {
            if (extra == null || extra.getType().isAir()) {
                continue;
            }
            killer.getWorld().dropItemNaturally(killer.getLocation(), extra);
        }
        killer.sendMessage(plugin.config().message(
                "prize-claimed",
                Placeholder.unparsed("prize", Integer.toString(amount)),
                Placeholder.unparsed("prize-name", plugin.config().killPrizeName())
        ));
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
        Item item = findTrackedGroundItem();
        if (item != null) {
            return item.getLocation();
        }
        for (Item extra : findAllLoadedCapeItems()) {
            return extra.getLocation();
        }
        Location stored = store.groundLocation(plugin.getServer());
        if (stored != null) {
            return stored;
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
        clearGrace();
        removeLoadedGroundCapes();
        holderId = player.getUniqueId();
        wearStartedMillis = System.currentTimeMillis();
        holdRewardPayouts = 0L;
        boundsWarned = false;
        store.saveHolder(holderId, wearStartedMillis);
        store.saveNow();
        applyGlide(player);
        stripCapesFromNonHolders();
    }

    public void returnCape(String reason) {
        Player previous = holder();
        UUID previousId = holderId;
        String previousName = previous != null ? previous.getName() : store.lastKnownName(previousId);
        if (previousId != null) {
            finishWear(previousId, previousName);
        }
        if (previous != null) {
            clearBoundsTitle(previous);
            applySlowFallIfHigh(previous);
            removeFromInventory(previous);
            previous.setGliding(false);
        }
        holderId = null;
        wearStartedMillis = 0L;
        holdRewardPayouts = 0L;
        boundsWarned = false;
        clearGrace();
        store.saveHolder(null, 0L);
        store.saveNow();
        removeLoadedGroundCapes();
        reconcileGroundCapes();
        plugin.getLogger().info("Spawn Cape returned to spawn (" + reason + ").");
    }

    public boolean tryTransferTo(Player killer) {
        if (killer == null) {
            returnCape("death, no killer");
            return false;
        }
        payoutKillPrize(killer);
        if (!canReceive(killer)) {
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
            clearBoundsTitle(previous);
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
        return remainingToBounds(location) < 0.0;
    }

    /**
     * Blocks remaining until any axis exceeds the world limit (Chebyshev).
     * Negative means already out of bounds. End/other worlds are 0 (instant return).
     */
    public double remainingToBounds(Location location) {
        if (location.getWorld() == null) {
            return -1.0;
        }
        String name = location.getWorld().getName();
        double limit;
        if (name.equals(plugin.config().overworldName())) {
            limit = plugin.config().overworldLimit();
        } else if (name.equals(plugin.config().netherName())) {
            limit = plugin.config().netherLimit();
        } else {
            return -1.0;
        }
        double maxAbs = Math.max(Math.abs(location.getX()),
                Math.max(Math.abs(location.getY()), Math.abs(location.getZ())));
        return limit - maxAbs;
    }

    public String closestBoundsAxis(Location location) {
        if (location.getWorld() == null) {
            return "?";
        }
        double ax = Math.abs(location.getX());
        double ay = Math.abs(location.getY());
        double az = Math.abs(location.getZ());
        if (ax >= ay && ax >= az) {
            return "x";
        }
        if (ay >= az) {
            return "y";
        }
        return "z";
    }

    private void warnIfNearBounds(Player holder) {
        Location loc = holder.getLocation();
        double remaining = remainingToBounds(loc);
        double warnAt = plugin.config().boundsWarnBlocks();
        if (warnAt <= 0.0 || remaining > warnAt || remaining <= 0.0) {
            clearBoundsTitle(holder);
            return;
        }
        int blocks = Math.max(1, (int) Math.ceil(remaining));
        var resolvers = new TagResolver[] {
                Placeholder.unparsed("remaining", Integer.toString(blocks)),
                Placeholder.unparsed("axis", closestBoundsAxis(loc))
        };
        holder.showTitle(Title.title(
                plugin.config().rawMessage("bounds-warn", resolvers),
                plugin.config().rawMessage("bounds-warn-subtitle", resolvers),
                Title.Times.times(Duration.ZERO, Duration.ofMillis(1200), Duration.ofMillis(200))
        ));
        boundsWarned = true;
    }

    private void clearBoundsTitle(Player holder) {
        if (!boundsWarned) {
            return;
        }
        holder.resetTitle();
        boundsWarned = false;
    }

    private void applySlowFallIfHigh(Player player) {
        if (player == null || player.isDead()) {
            return;
        }
        int seconds = plugin.config().slowFallSeconds();
        double minHeight = plugin.config().slowFallBlocks();
        if (seconds <= 0 || minHeight <= 0.0) {
            return;
        }
        if (blocksAboveGround(player) < minHeight) {
            return;
        }
        player.addPotionEffect(new PotionEffect(
                PotionEffectType.SLOW_FALLING,
                seconds * 20,
                0,
                true,
                false,
                true
        ));
    }

    private double blocksAboveGround(Player player) {
        if (player.isOnGround() || player.isInWater() || player.isClimbing()) {
            return 0.0;
        }
        Location loc = player.getLocation();
        World world = loc.getWorld();
        if (world == null) {
            return 0.0;
        }
        var hit = world.rayTraceBlocks(
                loc,
                new org.bukkit.util.Vector(0, -1, 0),
                512.0,
                FluidCollisionMode.ALWAYS,
                true
        );
        if (hit == null || hit.getHitPosition() == null) {
            return 512.0;
        }
        return Math.max(0.0, loc.getY() - hit.getHitPosition().getY());
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
        if (holder == null || isGraceActive()) {
            return;
        }
        Location loc = holder.getLocation();
        String duration = formatDuration(currentHoldSeconds());
        String prize = Integer.toString(currentKillPrize());
        String prizeName = plugin.config().killPrizeName();
        Component message = plugin.config().message(
                "broadcast",
                plugin.config().locationResolvers(holder.getName(), loc, duration, prize, prizeName)
        );
        String plain = plugin.config().plainBroadcast(
                holder.getName(),
                loc.getBlockX(),
                loc.getBlockY(),
                loc.getBlockZ(),
                loc.getWorld() == null ? "unknown" : loc.getWorld().getName(),
                duration,
                prize,
                prizeName
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
                    plugin.config().locationResolvers(
                            holder.getName(),
                            holder.getLocation(),
                            formatDuration(currentHoldSeconds()),
                            Integer.toString(currentKillPrize()),
                            plugin.config().killPrizeName())
            ));
            return;
        }
        viewer.sendMessage(plugin.config().message(
                "location-ground",
                plugin.config().locationResolvers("Spawn Cape", currentLocation())
        ));
    }

    public void ensureSpawnItem() {
        reconcileGroundCapes();
    }

    public void handleChunkLoad(Chunk chunk) {
        boolean returnChunk = isReturnChunk(chunk);
        boolean sawCape = false;
        for (Entity entity : chunk.getEntities()) {
            if (entity instanceof Item item && plugin.capeItem().isCape(item.getItemStack())) {
                sawCape = true;
                break;
            }
        }
        if (sawCape || (returnChunk && holderId == null)) {
            reconcileGroundCapes();
        }
    }

    private void tick() {
        Player holder = holder();
        if (holder != null && !isGraceActive()) {
            enforceOffhand(holder);
            applyGlide(holder);
            awardMilestones(holder);
            awardHoldReward(holder);
            stripCapeFromEnderChest(holder);
            stripCapesFromNonHolders();
            if (!findAllLoadedCapeItems().isEmpty()) {
                removeLoadedGroundCapes();
            }
            warnIfNearBounds(holder);
            if (isOutOfBounds(holder.getLocation())) {
                returnCape("out of bounds");
            }
            return;
        }
        if (holderId != null) {
            if (isGraceActive()) {
                stripCapesFromNonHolders();
                return;
            }
            String reason = graceExpireReason != null ? graceExpireReason : "holder offline";
            returnCape(reason);
            return;
        }
        stripCapesFromNonHolders();
        reconcileGroundCapes();
    }

    private void recoverState() {
        UUID saved = store.holder();
        wearStartedMillis = store.wearStartedMillis();
        graceUntilMillis = store.graceUntilMillis();
        Player onlineHolder = saved != null ? plugin.getServer().getPlayer(saved) : null;
        if (onlineHolder != null) {
            holderId = saved;
            restoreSession(onlineHolder, false);
            return;
        }
        if (saved != null) {
            holderId = saved;
            long now = System.currentTimeMillis();
            if (graceUntilMillis > 0L && now >= graceUntilMillis) {
                returnCape("grace expired while offline");
                return;
            }
            if (graceUntilMillis > now) {
                startGraceUntil(graceUntilMillis, "reconnect grace expired");
                return;
            }
            long reboot = plugin.config().rebootGraceSeconds();
            if (reboot > 0L) {
                startGracePeriod(reboot, "reboot grace expired");
                return;
            }
            returnCape("holder offline after restart");
            return;
        }
        holderId = null;
        wearStartedMillis = 0L;
        graceUntilMillis = 0L;
        store.saveHolder(null, 0L);
        store.saveNow();
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            removeFromInventory(player);
        }
        removeLoadedGroundCapes();
        reconcileGroundCapes();
    }

    public void handlePlayerJoin(Player player) {
        stripCapeFromEnderChest(player);
        if (tryRestoreAfterReboot(player)) {
            return;
        }
        if (findCapeInInventory(player) != null) {
            removeFromInventory(player);
            plugin.getLogger().info("Removed leftover Spawn Cape from " + player.getName()
                    + " because they are not the reserved holder.");
        }
    }

    public boolean tryRestoreAfterReboot(Player player) {
        UUID saved = holderId != null ? holderId : store.holder();
        if (saved == null || !saved.equals(player.getUniqueId())) {
            return false;
        }
        long until = graceUntilMillis > 0L ? graceUntilMillis : store.graceUntilMillis();
        if (until > 0L && System.currentTimeMillis() >= until) {
            return false;
        }
        Player current = holder();
        if (current != null && !current.getUniqueId().equals(player.getUniqueId())) {
            return false;
        }
        boolean alreadyHolding = isHolder(player) && findCapeInInventory(player) != null;
        restoreSession(player, !alreadyHolding);
        return true;
    }

    private void restoreSession(Player player, boolean announce) {
        clearGrace();
        holderId = player.getUniqueId();
        if (wearStartedMillis <= 0L) {
            wearStartedMillis = store.wearStartedMillis();
        }
        if (wearStartedMillis <= 0L) {
            wearStartedMillis = System.currentTimeMillis();
        }
        removeLoadedGroundCapes();
        enforceOffhand(player);
        store.saveHolder(holderId, wearStartedMillis);
        store.saveNow();
        stripCapesFromNonHolders();
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

    public void beginReconnectGrace() {
        long seconds = plugin.config().reconnectGraceSeconds();
        if (seconds <= 0L) {
            returnCape("logout");
            return;
        }
        store.saveHolder(holderId, wearStartedMillis);
        store.setGroundItemId(groundItemId);
        startGracePeriod(seconds, "reconnect grace expired");
    }

    public void beginRebootGrace() {
        long seconds = plugin.config().rebootGraceSeconds();
        store.saveHolder(holderId, wearStartedMillis);
        store.setGroundItemId(groundItemId);
        if (seconds <= 0L) {
            store.setGraceUntil(0L);
            store.saveNow();
            return;
        }
        startGracePeriod(seconds, "reboot grace expired");
    }

    private void startGracePeriod(long seconds, String expireReason) {
        long until = System.currentTimeMillis() + Math.max(0L, seconds) * 1000L;
        startGraceUntil(until, expireReason);
    }

    private void startGraceUntil(long untilMillis, String expireReason) {
        cancelGraceTask();
        graceExpireReason = expireReason;
        graceUntilMillis = untilMillis;
        store.setGraceUntil(untilMillis);
        store.saveHolder(holderId, wearStartedMillis);
        store.saveNow();
        long remainingMs = untilMillis - System.currentTimeMillis();
        if (remainingMs <= 0L) {
            graceUntilMillis = 0L;
            returnCape(expireReason);
            return;
        }
        long ticks = Math.max(1L, (remainingMs + 49L) / 50L);
        long seconds = Math.max(1L, (remainingMs + 999L) / 1000L);
        plugin.getLogger().info("Waiting " + seconds
                + "s for the Spawn Cape holder to rejoin (" + expireReason + ").");
        graceTask = plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            graceTask = null;
            if (holder() != null) {
                clearGrace();
                return;
            }
            returnCape(expireReason);
        }, ticks);
    }

    private void cancelGraceTask() {
        if (graceTask != null) {
            graceTask.cancel();
            graceTask = null;
        }
    }

    private void clearGrace() {
        cancelGraceTask();
        graceUntilMillis = 0L;
        store.setGraceUntil(0L);
    }

    private boolean isGraceActive() {
        return graceUntilMillis > System.currentTimeMillis() || graceTask != null;
    }

    public boolean shouldKeepGliding(Player player) {
        return !player.isOnGround() && !player.isInWater() && !player.isSwimming();
    }

    private void applyGlide(Player player) {
        if (shouldKeepGliding(player) && !player.isGliding()) {
            player.setGliding(true);
        }
    }

    private boolean isJumping(Player player) {
        try {
            return player.getCurrentInput().isJump();
        } catch (Throwable ignored) {
            return false;
        }
    }

    private boolean needsAssist(Player player) {
        return shouldKeepGliding(player)
                && !player.isGliding()
                && player.getVelocity().getY() < -0.4
                && plugin.getServer().getCurrentTick() % 20 == 0;
    }

    private void tickGlide() {
        Player holder = holder();
        if (holder == null) {
            Item ground = findTrackedGroundItem();
            if (ground != null) {
                snapGroundItem(ground);
            }
            return;
        }
        if (isGraceActive()) {
            return;
        }
        switch (plugin.config().glideMode()) {
            case JUMP -> {
                if (isJumping(holder)) {
                    applyGlide(holder);
                }
            }
            case ASSIST -> {
                if (isJumping(holder) || needsAssist(holder)) {
                    applyGlide(holder);
                }
            }
            case FORCE -> applyGlide(holder);
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

    public void reconcileGroundCapes() {
        if (holderId != null) {
            removeLoadedGroundCapes();
            stripCapesFromNonHolders();
            return;
        }
        if (dropQueued || loadingGroundChunk) {
            return;
        }
        Item keep = findTrackedGroundItem();
        if (keep == null) {
            List<Item> found = findAllLoadedCapeItems();
            if (!found.isEmpty()) {
                keep = selectKeep(found);
            }
        }
        if (keep != null) {
            adoptAndAnchor(keep);
            removeOtherGroundCapes(keep);
            return;
        }
        Location last = store.groundLocation(plugin.getServer());
        if (last != null && last.getWorld() != null) {
            World world = last.getWorld();
            int chunkX = last.getBlockX() >> 4;
            int chunkZ = last.getBlockZ() >> 4;
            if (!world.isChunkLoaded(chunkX, chunkZ)) {
                loadingGroundChunk = true;
                world.getChunkAtAsync(last).thenAccept(chunk -> {
                    if (!plugin.isEnabled()) {
                        return;
                    }
                    plugin.getServer().getScheduler().runTask(plugin, () -> {
                        loadingGroundChunk = false;
                        if (plugin.isEnabled()) {
                            reconcileGroundCapes();
                        }
                    });
                }).exceptionally(ex -> {
                    plugin.getServer().getScheduler().runTask(plugin, () -> {
                        loadingGroundChunk = false;
                        if (plugin.isEnabled() && holderId == null) {
                            dropAtSpawn();
                        }
                    });
                    return null;
                });
                return;
            }
        }
        dropAtSpawn();
    }

    private void dropAtSpawn() {
        if (holderId != null || dropQueued || loadingGroundChunk) {
            return;
        }
        Item existing = findTrackedGroundItem();
        if (existing != null) {
            adoptAndAnchor(existing);
            return;
        }
        List<Item> found = findAllLoadedCapeItems();
        if (!found.isEmpty()) {
            Item keep = selectKeep(found);
            adoptAndAnchor(keep);
            removeOtherGroundCapes(keep);
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
            if (!plugin.isEnabled() || holderId != null) {
                return;
            }
            Item tracked = findTrackedGroundItem();
            if (tracked != null) {
                adoptAndAnchor(tracked);
                return;
            }
            List<Item> loaded = findAllLoadedCapeItems();
            if (!loaded.isEmpty()) {
                Item keep = selectKeep(loaded);
                adoptAndAnchor(keep);
                removeOtherGroundCapes(keep);
                return;
            }
            ItemStack stack = plugin.capeItem().create();
            plugin.capeItem().refreshLore(stack);
            Item dropped = world.dropItem(location, stack, this::styleGroundItem);
            dropped.setVelocity(new org.bukkit.util.Vector(0, 0, 0));
            dropped.teleport(location);
            adoptAndAnchor(dropped);
        }));
    }

    public void styleGroundItem(Item item) {
        ItemStack stack = item.getItemStack();
        if (stack.getAmount() != 1) {
            stack.setAmount(1);
            item.setItemStack(stack);
        }
        item.setUnlimitedLifetime(true);
        item.setInvulnerable(true);
        item.setGlowing(true);
        item.setCanMobPickup(false);
        item.setPickupDelay(0);
        item.setGravity(false);
        item.setPersistent(true);
        item.setVelocity(new org.bukkit.util.Vector(0, 0, 0));
        item.customName(plugin.config().itemName());
        item.setCustomNameVisible(true);
    }

    private void adoptAndAnchor(Item item) {
        styleGroundItem(item);
        snapGroundItem(item);
        UUID id = item.getUniqueId();
        if (!id.equals(groundItemId)) {
            groundItemId = id;
            store.setGroundItemId(id);
            store.setGroundLocation(item.getLocation());
            return;
        }
        persistGroundLocation(item.getLocation());
    }

    private void snapGroundItem(Item item) {
        Location dest = plugin.config().returnLocation();
        if (dest.getWorld() == null) {
            return;
        }
        item.setGravity(false);
        item.setVelocity(new org.bukkit.util.Vector(0, 0, 0));
        item.teleport(dest);
        item.setVelocity(new org.bukkit.util.Vector(0, 0, 0));
        styleGroundItem(item);
    }

    private void persistGroundLocation(Location loc) {
        Location stored = store.groundLocation(plugin.getServer());
        if (stored != null
                && stored.getWorld() != null
                && loc.getWorld() != null
                && stored.getWorld().equals(loc.getWorld())
                && stored.getBlockX() == loc.getBlockX()
                && stored.getBlockY() == loc.getBlockY()
                && stored.getBlockZ() == loc.getBlockZ()) {
            return;
        }
        store.setGroundLocation(loc);
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
        return null;
    }

    private List<Item> findAllLoadedCapeItems() {
        List<Item> found = new ArrayList<>();
        for (World world : plugin.getServer().getWorlds()) {
            for (Item item : world.getEntitiesByClass(Item.class)) {
                if (!item.isDead() && plugin.capeItem().isCape(item.getItemStack())) {
                    found.add(item);
                }
            }
        }
        return found;
    }

    private Item selectKeep(List<Item> found) {
        if (found.isEmpty()) {
            return null;
        }
        if (groundItemId != null) {
            for (Item item : found) {
                if (item.getUniqueId().equals(groundItemId)) {
                    return item;
                }
            }
        }
        Location spawn = plugin.config().returnLocation();
        World spawnWorld = spawn.getWorld();
        Item best = found.getFirst();
        double bestDist = Double.MAX_VALUE;
        for (Item item : found) {
            Location loc = item.getLocation();
            if (spawnWorld == null || loc.getWorld() == null || !spawnWorld.equals(loc.getWorld())) {
                continue;
            }
            double dist = loc.distanceSquared(spawn);
            if (dist < bestDist) {
                bestDist = dist;
                best = item;
            }
        }
        return best;
    }

    private void removeOtherGroundCapes(Item keep) {
        UUID keepId = keep.getUniqueId();
        for (Item item : findAllLoadedCapeItems()) {
            if (!item.getUniqueId().equals(keepId)) {
                item.remove();
            }
        }
    }

    private void removeLoadedGroundCapes() {
        for (Item item : findAllLoadedCapeItems()) {
            item.remove();
        }
        clearGroundTracking();
    }

    private void clearGroundTracking() {
        groundItemId = null;
        store.setGroundItemId(null);
        store.setGroundLocation(null);
    }

    private void stripCapesFromNonHolders() {
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            if (isHolder(player)) {
                continue;
            }
            if (findCapeInInventory(player) != null) {
                removeFromInventory(player);
            } else {
                stripCapeFromEnderChest(player);
            }
        }
    }

    private boolean isReturnChunk(Chunk chunk) {
        Location spawn = plugin.config().returnLocation();
        return spawn.getWorld() != null
                && chunk.getWorld().equals(spawn.getWorld())
                && chunk.getX() == plugin.config().returnChunkX()
                && chunk.getZ() == plugin.config().returnChunkZ();
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
        ItemStack[] armor = inventory.getArmorContents();
        if (armor != null) {
            for (ItemStack stack : armor) {
                if (plugin.capeItem().isCape(stack)) {
                    return stack;
                }
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
        ItemStack[] armor = inventory.getArmorContents();
        boolean armorChanged = false;
        if (armor != null) {
            for (int i = 0; i < armor.length; i++) {
                if (plugin.capeItem().isCape(armor[i])) {
                    armor[i] = null;
                    armorChanged = true;
                }
            }
            if (armorChanged) {
                inventory.setArmorContents(armor);
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
        return days + "d " + hours + "h " + minutes + "m";
    }

    private static boolean exceeds(double x, double y, double z, double limit) {
        return Math.abs(x) > limit || Math.abs(y) > limit || Math.abs(z) > limit;
    }
}
