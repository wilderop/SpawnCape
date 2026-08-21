package com.lawlessmc.spawncape.command;

import com.lawlessmc.spawncape.SpawnCapePlugin;
import com.lawlessmc.spawncape.manager.GlideMode;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
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
            sender.sendMessage(plugin.config().message("hint"));
            return true;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "help" -> plugin.config().sendHelp(sender);
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
            case "glide" -> {
                if (!sender.isOp()) {
                    sender.sendMessage(plugin.config().message("no-permission"));
                    return true;
                }
                if (args.length == 1) {
                    sender.sendMessage(plugin.config().message(
                            "glide-mode",
                            Placeholder.unparsed("mode", plugin.config().glideMode().configName())
                    ));
                    return true;
                }
                GlideMode mode = GlideMode.parse(args[1]);
                if (mode == null) {
                    sender.sendMessage(plugin.config().message("glide-usage"));
                    return true;
                }
                plugin.config().setGlideMode(mode);
                sender.sendMessage(plugin.config().message(
                        "glide-set",
                        Placeholder.unparsed("mode", mode.configName())
                ));
            }
            default -> sender.sendMessage("Usage: /cape [help|off|on|reload|glide]");
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            String prefix = args[0].toLowerCase(Locale.ROOT);
            return List.of("help", "off", "on", "reload", "glide").stream()
                    .filter(option -> option.startsWith(prefix))
                    .filter(option -> sender.isOp() || (!option.equals("reload") && !option.equals("glide")))
                    .toList();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("glide") && sender.isOp()) {
            String prefix = args[1].toLowerCase(Locale.ROOT);
            return List.of("force", "jump", "assist").stream()
                    .filter(option -> option.startsWith(prefix))
                    .toList();
        }
        return List.of();
    }
}
