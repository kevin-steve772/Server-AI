package com.serverai.command;

import com.serverai.Main;
import net.kyori.adventure.text.Component;
import net.md_5.bungee.api.ChatColor;
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
            sender.sendMessage(Component.text(msg("messages.no-permission", "&c你没有权限执行此命令。")));
            return true;
        }

        if (args.length == 0) {
            sender.sendMessage(Component.text(msg("messages.usage", "&e用法: /ask <问题>")));
            return true;
        }

        if (args[0].equalsIgnoreCase("reload")) {
            if (!sender.hasPermission("serverai.reload")) {
                sender.sendMessage(Component.text(msg("messages.no-permission", "&c你没有权限执行此命令。")));
                return true;
            }
            plugin.reloadPluginConfig();
            sender.sendMessage(Component.text(msg("messages.reloaded", "&a配置已重载。")));
            return true;
        }

        String apiKey = plugin.getConfig().getString("api.key", "");
        if (apiKey.isEmpty() || "your-api-key-here".equals(apiKey)) {
            sender.sendMessage(Component.text(msg("messages.no-key", "&cAPI密钥未配置，请设置 config.yml 中的 api.key")));
            return true;
        }

        if (sender instanceof Player player) {
            if (!plugin.checkCooldown(player.getUniqueId())) {
                long remaining = plugin.getRemainingCooldown(player.getUniqueId());
                String cd = msg("messages.cooldown", "&c请等待 %seconds% 秒后再询问。")
                        .replace("%seconds%", String.valueOf(remaining));
                sender.sendMessage(Component.text(cd));
                return true;
            }
        }

        String question = String.join(" ", args);
        String senderName = sender instanceof Player p ? p.getName() : "Console";
        String bcQuestion = msg("messages.broadcast-question", "&6[提问] &e%sender%&6: &f%question%")
                .replace("%sender%", senderName)
                .replace("%question%", question);

        plugin.getServer().broadcast(Component.text(bcQuestion));
        sender.sendMessage(Component.text(msg("messages.thinking", "&e正在思考中，请稍候...")));

        plugin.getServer().getGlobalRegionScheduler().run(plugin, task -> {
            try {
                String answer = plugin.getAiClient().ask(question);
                plugin.getServer().getGlobalRegionScheduler().run(plugin, t -> {
                    if (plugin.getNpcManager().isSpawned()) {
                        plugin.getNpcManager().say(answer);
                    } else {
                        String bcAnswer = msg("messages.broadcast-answer", "&b[AI] %answer%")
                                .replace("%answer%", answer);
                        plugin.getServer().broadcast(Component.text(bcAnswer));
                    }
                });
            } catch (IllegalStateException e) {
                plugin.getServer().getGlobalRegionScheduler().run(plugin, t -> {
                    sender.sendMessage(Component.text(msg("messages.no-key", "&cAPI密钥未配置")));
                });
            } catch (Exception e) {
                plugin.getServer().getGlobalRegionScheduler().run(plugin, t -> {
                    String err = msg("messages.error", "&c请求AI时出错: %error%")
                            .replace("%error%", e.getMessage() != null ? e.getMessage() : "未知错误");
                    sender.sendMessage(Component.text(err));
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

    private String msg(String path, String def) {
        return ChatColor.translateAlternateColorCodes('&',
                plugin.getConfig().getString(path, def));
    }
}
