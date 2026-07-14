package com.serverai.command;

import com.serverai.Main;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.List;

public class AskCommand implements TabExecutor {

    private final Main plugin;

    public AskCommand(Main plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!sender.hasPermission("serverai.ask")) {
            sendMessage(sender, plugin.getConfig().getString("messages.no-permission", "&c你没有权限执行此命令。"));
            return true;
        }

        if (args.length == 0) {
            sendMessage(sender, plugin.getConfig().getString("messages.usage", "&e用法: /ask <问题>"));
            return true;
        }

        if (args[0].equalsIgnoreCase("reload")) {
            if (!sender.hasPermission("serverai.reload")) {
                sendMessage(sender, plugin.getConfig().getString("messages.no-permission", "&c你没有权限执行此命令。"));
                return true;
            }
            plugin.reloadPluginConfig();
            sendMessage(sender, plugin.getConfig().getString("messages.reloaded", "&a配置已重载。"));
            return true;
        }

        String apiKey = plugin.getConfig().getString("api.key", "");
        if (apiKey.isEmpty() || "your-api-key-here".equals(apiKey)) {
            sendMessage(sender, plugin.getConfig().getString("messages.no-key", "&cAPI密钥未配置，请设置 config.yml 中的 api.key"));
            return true;
        }

        if (sender instanceof Player player) {
            if (!plugin.checkCooldown(player.getUniqueId())) {
                long remaining = plugin.getRemainingCooldown(player.getUniqueId());
                String msg = plugin.getConfig().getString("messages.cooldown", "&c请等待 %seconds% 秒后再询问。")
                        .replace("%seconds%", String.valueOf(remaining));
                sendMessage(sender, msg);
                return true;
            }
        }

        String question = String.join(" ", args);
        sendMessage(sender, plugin.getConfig().getString("messages.thinking", "&e正在思考中，请稍候..."));

        plugin.getServer().getGlobalRegionScheduler().run(plugin, task -> {
            try {
                String answer = plugin.getAiClient().ask(question);
                plugin.getServer().getGlobalRegionScheduler().run(plugin, t -> {
                    sender.sendMessage(Component.text("§e[AI] §r" + answer));
                });
            } catch (IllegalStateException e) {
                plugin.getServer().getGlobalRegionScheduler().run(plugin, t -> {
                    String msg = plugin.getConfig().getString("messages.no-key", "&cAPI密钥未配置，请设置 config.yml 中的 api.key");
                    sendMessage(sender, msg);
                });
            } catch (Exception e) {
                plugin.getServer().getGlobalRegionScheduler().run(plugin, t -> {
                    String msg = plugin.getConfig().getString("messages.error", "&c请求AI时出错: %error%")
                            .replace("%error%", e.getMessage() != null ? e.getMessage() : "未知错误");
                    sendMessage(sender, msg);
                });
            }
        });

        return true;
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (args.length == 1 && sender.hasPermission("serverai.reload")) {
            return List.of("reload");
        }
        return Collections.emptyList();
    }

    private void sendMessage(CommandSender sender, String message) {
        sender.sendMessage(Component.text(net.md_5.bungee.api.ChatColor.translateAlternateColorCodes('&', message)));
    }
}
