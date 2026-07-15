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

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class NpcCommand implements TabExecutor {

    private final Main plugin;

    public NpcCommand(Main plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!sender.hasPermission("serverai.npc")) {
            sender.sendMessage(Component.text(color(plugin.getConfig().getString("messages.no-permission", "&c你没有权限"))));
            return true;
        }

        if (args.length == 0) {
            sender.sendMessage(Component.text(color("&e用法: /npc spawn &7- 生成NPC    &e/npc remove &7- 移除NPC    &e/npc say <消息> &7- NPC说话")));
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "spawn" -> {
                if (!(sender instanceof Player p)) {
                    sender.sendMessage(Component.text("&c只有玩家才能执行此命令"));
                    return true;
                }
                if (plugin.getNpcManager().spawn(p.getLocation())) {
                    sender.sendMessage(Component.text(color("&aNPC已生成在当前位置")));
                } else {
                    sender.sendMessage(Component.text(color("&cNPC生成失败")));
                }
            }
            case "remove" -> {
                plugin.getNpcManager().despawn();
                sender.sendMessage(Component.text(color("&aNPC已移除")));
            }
            case "say" -> {
                if (args.length < 2) {
                    sender.sendMessage(Component.text(color("&e用法: /npc say <消息>")));
                    return true;
                }
                String message = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
                plugin.getNpcManager().say(message);
            }
            default -> {
                sender.sendMessage(Component.text(color("&e用法: /npc spawn &7- 生成NPC    &e/npc remove &7- 移除NPC    &e/npc say <消息> &7- NPC说话")));
            }
        }
        return true;
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (args.length == 1) {
            return List.of("spawn", "remove", "say");
        }
        return Collections.emptyList();
    }

    private String color(String s) {
        return ChatColor.translateAlternateColorCodes('&', s);
    }
}
