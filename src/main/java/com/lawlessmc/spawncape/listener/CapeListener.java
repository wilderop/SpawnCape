package com.lawlessmc.spawncape.listener;

import com.lawlessmc.spawncape.SpawnCapePlugin;
import com.lawlessmc.spawncape.manager.CapeManager;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryPickupItemEvent;
import org.bukkit.event.player.PlayerAttemptPickupItemEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

public final class CapeListener implements Listener {

    private final SpawnCapePlugin plugin;

    public CapeListener(SpawnCapePlugin plugin) {
        this.plugin = plugin;
    }

    private CapeManager manager() {
        return plugin.capeManager();
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onAttemptPickup(PlayerAttemptPickupItemEvent event) {
        ItemStack stack = event.getItem().getItemStack();
        if (!plugin.capeItem().isCape(stack)) {
            return;
        }
        Player player = event.getPlayer();
        if (!manager().canReceive(player)) {
            event.setCancelled(true);
            player.sendMessage(plugin.config().message("cannot-pickup"));
            return;
        }
        event.setCancelled(true);
        event.getItem().remove();
        manager().tryClaim(player);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityPickup(EntityPickupItemEvent event) {
        if (!(event.getEntity() instanceof Player)
                && plugin.capeItem().isCape(event.getItem().getItemStack())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        if (plugin.capeItem().isCape(event.getItemDrop().getItemStack())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onSwap(PlayerSwapHandItemsEvent event) {
        if (plugin.capeItem().isCape(event.getOffHandItem()) || plugin.capeItem().isCape(event.getMainHandItem())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onClick(InventoryClickEvent event) {
        ItemStack current = event.getCurrentItem();
        ItemStack cursor = event.getCursor();
        if (plugin.capeItem().isCape(current) || plugin.capeItem().isCape(cursor)) {
            event.setCancelled(true);
        }
        if (event.getHotbarButton() >= 0 && event.getWhoClicked() instanceof Player player) {
            ItemStack hotbar = player.getInventory().getItem(event.getHotbarButton());
            if (plugin.capeItem().isCape(hotbar)) {
                event.setCancelled(true);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDrag(InventoryDragEvent event) {
        if (plugin.capeItem().isCape(event.getOldCursor()) || plugin.capeItem().isCape(event.getCursor())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onHopper(InventoryPickupItemEvent event) {
        if (plugin.capeItem().isCape(event.getItem().getItemStack())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.OFF_HAND) {
            return;
        }
        Player player = event.getPlayer();
        if (!manager().isHolder(player)) {
            return;
        }
        if (!plugin.capeItem().isCape(player.getInventory().getItemInOffHand())) {
            return;
        }
        switch (event.getAction()) {
            case RIGHT_CLICK_AIR, RIGHT_CLICK_BLOCK -> {
                event.setCancelled(true);
                manager().boost(player);
            }
            default -> {
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        if (manager().isHolder(event.getPlayer()) && manager().isOutOfBounds(event.getTo())) {
            plugin.getServer().getScheduler().runTask(plugin, () -> manager().returnCape("teleport out of bounds"));
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onFatalDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }
        if (!manager().isHolder(player)) {
            return;
        }
        if (isUnavoidable(event.getCause())) {
            return;
        }
        if (player.getHealth() - event.getFinalDamage() > 0.0) {
            return;
        }
        event.setCancelled(true);
        event.setDamage(0.0);
        manager().activateTotem(player, resolveKiller(event, player));
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDeath(PlayerDeathEvent event) {
        Player victim = event.getEntity();
        if (!manager().isHolder(victim)) {
            event.getDrops().removeIf(stack -> plugin.capeItem().isCape(stack));
            return;
        }
        event.getDrops().removeIf(stack -> plugin.capeItem().isCape(stack));
        manager().removeFromInventory(victim);
        Player killer = victim.getKiller();
        if (killer != null && !killer.getUniqueId().equals(victim.getUniqueId())) {
            manager().tryTransferTo(killer);
        } else {
            manager().returnCape("death");
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        if (manager().isHolder(event.getPlayer())) {
            manager().returnCape("logout");
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            Player player = event.getPlayer();
            if (manager().isHolder(player)) {
                manager().enforceOffhand(player);
            } else if (manager().findCapeInInventory(player) != null) {
                manager().removeFromInventory(player);
            }
        });
    }

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent event) {
        Location spawn = plugin.config().returnLocation();
        if (spawn.getWorld() == null || !event.getWorld().equals(spawn.getWorld())) {
            return;
        }
        if (event.getChunk().getX() != plugin.config().returnChunkX()
                || event.getChunk().getZ() != plugin.config().returnChunkZ()) {
            return;
        }
        if (manager().holderId() != null) {
            return;
        }
        plugin.getServer().getScheduler().runTask(plugin, () -> manager().ensureSpawnItem());
    }

    private static boolean isUnavoidable(EntityDamageEvent.DamageCause cause) {
        return switch (cause) {
            case VOID, KILL, SUICIDE, WORLD_BORDER -> true;
            default -> false;
        };
    }

    private static Player resolveKiller(EntityDamageEvent event, Player victim) {
        if (event instanceof EntityDamageByEntityEvent byEntity) {
            Player fromDamager = playerFrom(byEntity.getDamager());
            if (fromDamager != null && !fromDamager.getUniqueId().equals(victim.getUniqueId())) {
                return fromDamager;
            }
        }
        Player listed = victim.getKiller();
        if (listed != null && !listed.getUniqueId().equals(victim.getUniqueId())) {
            return listed;
        }
        return null;
    }

    private static Player playerFrom(Entity entity) {
        if (entity instanceof Player player) {
            return player;
        }
        if (entity instanceof Projectile projectile && projectile.getShooter() instanceof Player player) {
            return player;
        }
        return null;
    }
}
