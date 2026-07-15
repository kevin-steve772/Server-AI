package com.serverai.command;

import com.serverai.Main;
import net.kyori.adventure.text.Component;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public final class NpcCommand implements TabExecutor {

    private static final Component USAGE = Component.text(
            "用法: /npc spawn | /npc remove | /npc say <消息>");

    private final Main plugin;

    public NpcCommand(Main plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (!sender.hasPermission("serverai.npc")) {
            sender.sendMessage(plugin.getMessages().get(
                    "messages.no-permission", "&c你没有权限执行此命令。"));
            return true;
        }
        if (args.length == 0) {
            sender.sendMessage(USAGE);
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "spawn" -> spawn(sender);
            case "remove" -> {
                plugin.getNpcManager().despawn();
                sender.sendMessage(Component.text("NPC已移除"));
            }
            case "say" -> say(sender, args);
            default -> sender.sendMessage(USAGE);
        }
        return true;
    }

    private void spawn(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("只有玩家才能执行此命令"));
            return;
        }
        if (plugin.getNpcManager().spawn(player.getLocation())) {
            sender.sendMessage(Component.text("NPC已生成在当前位置"));
        } else {
            sender.sendMessage(Component.text("NPC生成失败"));
        }
    }

    private void say(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(Component.text("用法: /npc say <消息>"));
            return;
        }
        if (!plugin.getNpcManager().isSpawned()) {
            sender.sendMessage(plugin.getMessages().get(
                    "messages.npc-not-spawned", "&c请先生成 NPC。"));
            return;
        }
        String message = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
        plugin.getNpcManager().say(message);
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender,
                                                 @NotNull Command command,
                                                 @NotNull String label,
                                                 @NotNull String[] args) {
        if (args.length == 1) {
            return List.of("spawn", "remove", "say");
        }
        return Collections.emptyList();
    }
}
