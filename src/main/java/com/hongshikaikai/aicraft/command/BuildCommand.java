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
 * {@code /aibuild}（别名 {@code /aib}）命令。
 *
 * <ul>
 *   <li>{@code /aibuild <描述>} —— 让 AI 在准星指向的位置建造（权限 {@code aicraft.build}，默认 OP）</li>
 *   <li>{@code /aibuild undo [次数]} —— 撤销最近的 AI 建造</li>
 *   <li>{@code /aibuild cancel} —— 取消正在进行的施工</li>
 *   <li>{@code /aibuild help} —— 查看用法</li>
 *   <li>{@code /aibuild reload} —— 重载配置（权限 {@code aicraft.reload}）</li>
 * </ul>
 */
public final class BuildCommand implements CommandExecutor, TabCompleter {

    private static final List<String> SUBCOMMANDS = List.of("undo", "cancel", "help", "reload");

    private final AICraft plugin;

    public BuildCommand(AICraft plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(plugin.pluginConfig().message("players-only"));
            return true;
        }

        if (args.length == 0) {
            player.sendMessage(plugin.pluginConfig().message("build-usage"));
            return true;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "help" -> {
                player.sendMessage(plugin.pluginConfig().message("build-usage"));
                return true;
            }
            case "reload" -> {
                if (!player.hasPermission("aicraft.reload")) {
                    player.sendMessage(plugin.pluginConfig().message("no-permission"));
                    return true;
                }
                plugin.reloadPlugin();
                player.sendMessage(plugin.pluginConfig().message("reloaded"));
                return true;
            }
            case "undo" -> {
                if (!requireBuildPermission(player)) {
                    return true;
                }
                plugin.buildService().undo(player, args.length > 1 ? parseCount(args[1]) : 1);
                return true;
            }
            case "cancel" -> {
                if (!requireBuildPermission(player)) {
                    return true;
                }
                plugin.buildService().cancel(player);
                return true;
            }
            default -> {
                if (!requireBuildPermission(player)) {
                    return true;
                }
                plugin.buildService().submit(player, String.join(" ", args));
                return true;
            }
        }
    }

    private boolean requireBuildPermission(Player player) {
        if (player.hasPermission("aicraft.build")) {
            return true;
        }
        player.sendMessage(plugin.pluginConfig().message("no-permission"));
        return false;
    }

    private static int parseCount(String raw) {
        try {
            return Math.max(1, Math.min(16, Integer.parseInt(raw.trim())));
        } catch (NumberFormatException ex) {
            return 1;
        }
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                      @NotNull String alias, @NotNull String[] args) {
        if (args.length != 1) {
            return List.of();
        }
        String prefix = args[0].toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>(SUBCOMMANDS.size());
        for (String sub : SUBCOMMANDS) {
            if (sub.startsWith(prefix)) {
                out.add(sub);
            }
        }
        return out;
    }
}
