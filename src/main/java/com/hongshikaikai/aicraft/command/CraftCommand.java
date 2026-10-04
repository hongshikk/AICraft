package com.hongshikaikai.aicraft.command;

import com.hongshikaikai.aicraft.AICraft;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * {@code /aicraft}（别名 {@code /aic}）命令。
 *
 * <ul>
 *   <li>{@code /aicraft} —— 打开 AI 合成器（权限 {@code aicraft.use}，默认所有人可用）</li>
 *   <li>{@code /aicraft reload} —— 重载配置（权限 {@code aicraft.reload}，默认 OP）</li>
 * </ul>
 */
public final class CraftCommand implements CommandExecutor, TabCompleter {

    private final AICraft plugin;

    public CraftCommand(AICraft plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (args.length > 0 && args[0].equalsIgnoreCase("reload")) {
            if (!sender.hasPermission("aicraft.reload")) {
                sender.sendMessage(plugin.pluginConfig().message("no-permission"));
                return true;
            }
            plugin.reloadPlugin();
            sender.sendMessage(plugin.pluginConfig().message("reloaded"));
            return true;
        }

        if (!(sender instanceof Player player)) {
            sender.sendMessage(plugin.pluginConfig().message("players-only"));
            return true;
        }
        if (!player.hasPermission("aicraft.use")) {
            player.sendMessage(plugin.pluginConfig().message("no-permission"));
            return true;
        }

        // 规格：打开界面无提示
        plugin.openGui(player);
        return true;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                      @NotNull String alias, @NotNull String[] args) {
        if (args.length == 1 && sender.hasPermission("aicraft.reload")) {
            String prefix = args[0].toLowerCase(Locale.ROOT);
            if ("reload".startsWith(prefix)) {
                List<String> out = new ArrayList<>(1);
                out.add("reload");
                return out;
            }
        }
        return List.of();
    }
}
