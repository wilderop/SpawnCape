package com.lawlessmc.spawncape.command;

import com.lawlessmc.spawncape.SpawnCapePlugin;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Locale;

public final class CapeCommand implements CommandExecutor, TabCompleter {

    private final SpawnCapePlugin plugin;

    public CapeCommand(SpawnCapePlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            plugin.capeManager().sendStatus(sender);
            return true;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "off" -> {
                if (!(sender instanceof Player player)) {
                    sender.sendMessage("Players only.");
                    return true;
                }
                plugin.capeManager().setMuted(player.getUniqueId(), true);
                player.sendMessage(plugin.config().message("muted"));
            }
            case "on" -> {
                if (!(sender instanceof Player player)) {
                    sender.sendMessage("Players only.");
                    return true;
                }
                plugin.capeManager().setMuted(player.getUniqueId(), false);
                player.sendMessage(plugin.config().message("unmuted"));
            }
            case "reload" -> {
                if (!sender.isOp()) {
                    sender.sendMessage(plugin.config().message("no-permission"));
                    return true;
                }
                plugin.config().reload();
                sender.sendMessage(plugin.config().message("reloaded"));
            }
            default -> sender.sendMessage("Usage: /cape [off|on|reload]");
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            String prefix = args[0].toLowerCase(Locale.ROOT);
            return List.of("off", "on", "reload").stream()
                    .filter(option -> option.startsWith(prefix))
                    .filter(option -> sender.isOp() || !option.equals("reload"))
                    .toList();
        }
        return List.of();
    }
}
