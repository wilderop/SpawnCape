package com.lawlessmc.spawncape.item;

import com.lawlessmc.spawncape.SpawnCapePlugin;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.List;

public final class CapeItem {

    public static final String KEY_NAME = "spawn_cape";
    public static final String KEEPSAKE_KEY_NAME = "spawn_cape_keepsake";

    private final SpawnCapePlugin plugin;
    private final NamespacedKey key;
    private final NamespacedKey keepsakeKey;
    private final NamespacedKey reachBlockKey;
    private final NamespacedKey reachEntityKey;

    public CapeItem(SpawnCapePlugin plugin) {
        this.plugin = plugin;
        this.key = new NamespacedKey(plugin, KEY_NAME);
        this.keepsakeKey = new NamespacedKey(plugin, KEEPSAKE_KEY_NAME);
        this.reachBlockKey = new NamespacedKey(plugin, "cape_block_reach");
        this.reachEntityKey = new NamespacedKey(plugin, "cape_entity_reach");
    }

    public NamespacedKey key() {
        return key;
    }

    public boolean isCape(ItemStack stack) {
        if (stack == null) {
            return false;
        }
        Material type = stack.getType();
        if (type.isAir() || type != plugin.config().itemMaterial()) {
            return false;
        }
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) {
            return false;
        }
        return meta.getPersistentDataContainer().has(key, PersistentDataType.BYTE);
    }

    public boolean isKeepsake(ItemStack stack) {
        if (stack == null) {
            return false;
        }
        Material type = stack.getType();
        if (type.isAir() || type != plugin.config().itemMaterial()) {
            return false;
        }
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) {
            return false;
        }
        return meta.getPersistentDataContainer().has(keepsakeKey, PersistentDataType.BYTE);
    }

    public ItemStack create() {
        ItemStack stack = new ItemStack(plugin.config().itemMaterial());
        applyMeta(stack);
        return stack;
    }

    public ItemStack createKeepsake(String playerName, String durationLabel, String date) {
        ItemStack stack = new ItemStack(plugin.config().itemMaterial());
        String lore = playerName + " held the spawn cape for " + durationLabel + " on " + date + "!";
        stack.editMeta(meta -> {
            meta.getPersistentDataContainer().set(keepsakeKey, PersistentDataType.BYTE, (byte) 1);
            meta.displayName(plugin.config().keepsakeName().decoration(TextDecoration.ITALIC, false));
            meta.lore(List.of(Component.text(lore, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)));
        });
        return stack;
    }

    public void applyMeta(ItemStack stack) {
        stack.editMeta(meta -> {
            meta.getPersistentDataContainer().set(key, PersistentDataType.BYTE, (byte) 1);
            meta.displayName(plugin.config().itemName().decoration(TextDecoration.ITALIC, false));
            meta.lore(List.of(longestLore()));
            meta.setUnbreakable(true);
            meta.setEnchantmentGlintOverride(plugin.config().itemGlow());
            meta.addItemFlags(ItemFlag.HIDE_UNBREAKABLE, ItemFlag.HIDE_ATTRIBUTES);
            try {
                meta.setGlider(true);
            } catch (Throwable ignored) {
            }

            double bonus = plugin.config().reachBonus();
            meta.removeAttributeModifier(Attribute.BLOCK_INTERACTION_RANGE);
            meta.removeAttributeModifier(Attribute.ENTITY_INTERACTION_RANGE);
            meta.addAttributeModifier(Attribute.BLOCK_INTERACTION_RANGE, modifier(reachBlockKey, bonus));
            meta.addAttributeModifier(Attribute.ENTITY_INTERACTION_RANGE, modifier(reachEntityKey, bonus));
        });
    }

    public void refreshLore(ItemStack stack) {
        if (!isCape(stack)) {
            return;
        }
        stack.editMeta(meta -> meta.lore(List.of(longestLore())));
    }

    public Component longestLore() {
        String text = plugin.capeManager() == null
                ? "No one has worn me yet."
                : plugin.capeManager().longestLoreText();
        return Component.text(text, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false);
    }

    private AttributeModifier modifier(NamespacedKey namespacedKey, double amount) {
        return new AttributeModifier(
                namespacedKey,
                amount,
                AttributeModifier.Operation.ADD_NUMBER,
                EquipmentSlotGroup.OFFHAND
        );
    }
}
