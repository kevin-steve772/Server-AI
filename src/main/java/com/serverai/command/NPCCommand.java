package com.serverai.command;

import com.serverai.Main;
import com.serverai.npc.AINpc;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;

public class NPCCommand implements TabExecutor {

    private final Main plugin;

    public NPCCommand(Main plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!sender.hasPermission("serverai.npc")) {
            sendMessage(sender, "&c你没有权限管理 NPC。");
            return true;
        }

        if (args.length == 0) {
            sendHelp(sender);
            return true;
        }

        String sub = args[0].toLowerCase();

        switch (sub) {
            case "spawn" -> handleSpawn(sender, args);
            case "remove" -> handleRemove(sender, args);
            case "list" -> handleList(sender);
            case "chat" -> handleChat(sender, args);
            case "move" -> handleMove(sender, args);
            case "stop" -> handleStop(sender, args);
            case "look" -> handleLook(sender, args);
            case "equip" -> handleEquip(sender, args);
            case "teleport", "tp" -> handleTeleport(sender, args);
            case "info" -> handleInfo(sender, args);
            case "help" -> sendHelp(sender);
            default -> sendMessage(sender, "&c未知子命令: " + sub + "。使用 /npc help 查看帮助。");
        }
        return true;
    }

    private void handleSpawn(CommandSender sender, String[] args) {
        if (args.length < 5) {
            sendMessage(sender, "&e用法: /npc spawn <名称> <world> <x> <y> <z> [类型]");
            return;
        }

        String name = args[1];
        String worldName = args[2];
        double x, y, z;
        try {
            x = Double.parseDouble(args[3]);
            y = Double.parseDouble(args[4]);
            z = Double.parseDouble(args[5]);
        } catch (NumberFormatException e) {
            sendMessage(sender, "&c坐标必须是数字。");
            return;
        }

        String type = args.length > 6 ? args[6] : "PLAYER";

        if (!(sender instanceof Player player)) {
            sendMessage(sender, "&c只有玩家可以执行此命令。");
            return;
        }

        var world = player.getServer().getWorld(worldName);
        if (world == null) {
            sendMessage(sender, "&c世界不存在: " + worldName);
            return;
        }

        try {
            org.bukkit.entity.EntityType entityType = org.bukkit.entity.EntityType.valueOf(type.toUpperCase());
            var loc = new org.bukkit.Location(world, x, y, z);
            AINpc npc = plugin.getNpcManager().createNpc(name, loc, entityType);
            sendMessage(sender, "&aNPC 已创建: " + npc.getName() + " (" + npc.getId() + ")");
        } catch (IllegalArgumentException e) {
            sendMessage(sender, "&c无效的实体类型: " + type);
        }
    }

    private void handleRemove(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sendMessage(sender, "&e用法: /npc remove <名称>");
            return;
        }
        String name = args[1];
        var npc = plugin.getNpcManager().getNpcByName(name);
        if (npc == null) {
            sendMessage(sender, "&cNPC 不存在: " + name);
            return;
        }
        npc.destroy();
        sendMessage(sender, "&aNPC 已移除: " + name);
    }

    private void handleList(CommandSender sender) {
        var npcs = plugin.getNpcManager().getAllNpcs();
        if (npcs.isEmpty()) {
            sendMessage(sender, "&e没有 AI NPC。");
            return;
        }
        sendMessage(sender, "&eAI NPC 列表:");
        for (AINpc npc : npcs) {
            var loc = npc.getLocation();
            sendMessage(sender, "&7 - &f" + npc.getName() + " &7(&f" + npc.getId() + "&7) at &f"
                    + loc.getWorld().getName() + "," + (int)loc.getX() + "," + (int)loc.getY() + "," + (int)loc.getZ()
                    + " &7[&f" + npc.getCurrentAction() + "&7]");
        }
    }

    private void handleChat(CommandSender sender, String[] args) {
        if (args.length < 3) {
            sendMessage(sender, "&e用法: /npc chat <名称> <消息>");
            return;
        }
        String name = args[1];
        String msg = String.join(" ", Arrays.copyOfRange(args, 2, args.length));
        var npc = plugin.getNpcManager().getNpcByName(name);
        if (npc == null) {
            sendMessage(sender, "&cNPC 不存在: " + name);
            return;
        }
        npc.chat(msg);
        sendMessage(sender, "&aNPC " + name + " 说了: " + msg);
    }

    private void handleMove(CommandSender sender, String[] args) {
        if (args.length < 5) {
            sendMessage(sender, "&e用法: /npc move <名称> <world> <x> <y> <z> [速度]");
            return;
        }
        String name = args[1];
        String worldName = args[2];
        double x, y, z, speed = 1.0;
        try {
            x = Double.parseDouble(args[3]);
            y = Double.parseDouble(args[4]);
            z = Double.parseDouble(args[5]);
            if (args.length > 6) speed = Double.parseDouble(args[6]);
        } catch (NumberFormatException e) {
            sendMessage(sender, "&c坐标/速度必须是数字。");
            return;
        }

        var npc = plugin.getNpcManager().getNpcByName(name);
        var world = sender.getServer().getWorld(worldName);
        if (npc == null) {
            sendMessage(sender, "&cNPC 不存在: " + name);
            return;
        }
        if (world == null) {
            sendMessage(sender, "&c世界不存在: " + worldName);
            return;
        }

        var loc = new org.bukkit.Location(world, x, y, z);
        npc.moveTo(loc, speed);
        sendMessage(sender, "&aNPC " + name + " 正在移动至 " + x + "," + y + "," + z);
    }

    private void handleStop(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sendMessage(sender, "&e用法: /npc stop <名称>");
            return;
        }
        String name = args[1];
        var npc = plugin.getNpcManager().getNpcByName(name);
        if (npc == null) {
            sendMessage(sender, "&cNPC 不存在: " + name);
            return;
        }
        npc.stopMoving();
        sendMessage(sender, "&aNPC " + name + " 已停止移动。");
    }

    private void handleLook(CommandSender sender, String[] args) {
        if (args.length < 5) {
            sendMessage(sender, "&e用法: /npc look <名称> <world> <x> <y> <z>");
            return;
        }
        String name = args[1];
        String worldName = args[2];
        double x, y, z;
        try {
            x = Double.parseDouble(args[3]);
            y = Double.parseDouble(args[4]);
            z = Double.parseDouble(args[5]);
        } catch (NumberFormatException e) {
            sendMessage(sender, "&c坐标必须是数字。");
            return;
        }

        var npc = plugin.getNpcManager().getNpcByName(name);
        var world = sender.getServer().getWorld(worldName);
        if (npc == null) {
            sendMessage(sender, "&cNPC 不存在: " + name);
            return;
        }
        if (world == null) {
            sendMessage(sender, "&c世界不存在: " + worldName);
            return;
        }

        npc.lookAt(new org.bukkit.Location(world, x, y, z));
        sendMessage(sender, "&aNPC " + name + " 正在看向 " + x + "," + y + "," + z);
    }

    private void handleEquip(CommandSender sender, String[] args) {
        if (args.length < 3) {
            sendMessage(sender, "&e用法: /npc equip <名称> <材质> [自定义名] [数量]");
            return;
        }
        String name = args[1];
        String material = args[2];
        String customName = args.length > 3 ? args[3] : null;
        int amount = args.length > 4 ? Integer.parseInt(args[4]) : 1;

        var npc = plugin.getNpcManager().getNpcByName(name);
        if (npc == null) {
            sendMessage(sender, "&cNPC 不存在: " + name);
            return;
        }

        try {
            org.bukkit.Material mat = org.bukkit.Material.valueOf(material.toUpperCase());
            var item = new org.bukkit.inventory.ItemStack(mat, amount);
            if (customName != null) {
                var meta = item.getItemMeta();
                meta.displayName(Component.text(customName).color(NamedTextColor.GOLD));
                item.setItemMeta(meta);
            }
            npc.setEquipment(item);
            sendMessage(sender, "&aNPC " + name + " 手持 " + material);
        } catch (IllegalArgumentException e) {
            sendMessage(sender, "&c无效材质: " + material);
        }
    }

    private void handleTeleport(CommandSender sender, String[] args) {
        if (args.length < 5) {
            sendMessage(sender, "&e用法: /npc tp <名称> <world> <x> <y> <z>");
            return;
        }
        String name = args[1];
        String worldName = args[2];
        double x, y, z;
        try {
            x = Double.parseDouble(args[3]);
            y = Double.parseDouble(args[4]);
            z = Double.parseDouble(args[5]);
        } catch (NumberFormatException e) {
            sendMessage(sender, "&c坐标必须是数字。");
            return;
        }

        var npc = plugin.getNpcManager().getNpcByName(name);
        var world = sender.getServer().getWorld(worldName);
        if (npc == null) {
            sendMessage(sender, "&cNPC 不存在: " + name);
            return;
        }
        if (world == null) {
            sendMessage(sender, "&c世界不存在: " + worldName);
            return;
        }

        npc.getNpc().teleport(new org.bukkit.Location(world, x, y, z));
        sendMessage(sender, "&aNPC " + name + " 已传送至 " + x + "," + y + "," + z);
    }

    private void handleInfo(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sendMessage(sender, "&e用法: /npc info <名称>");
            return;
        }
        String name = args[1];
        var npc = plugin.getNpcManager().getNpcByName(name);
        if (npc == null) {
            sendMessage(sender, "&cNPC 不存在: " + name);
            return;
        }
        var loc = npc.getLocation();
        sendMessage(sender, "&e=== NPC 信息 ===");
        sendMessage(sender, "&7名称: &f" + npc.getName());
        sendMessage(sender, "&7UUID: &f" + npc.getId());
        sendMessage(sender, "&7位置: &f" + loc.getWorld().getName() + "," + (int)loc.getX() + "," + (int)loc.getY() + "," + (int)loc.getZ());
        sendMessage(sender, "&7状态: &f" + npc.getCurrentAction());
        sendMessage(sender, "&7已生成: &f" + (npc.isSpawned() ? "是" : "否"));
    }

    private void sendHelp(CommandSender sender) {
        sendMessage(sender, "&e=== /npc 命令帮助 ===");
        sendMessage(sender, "&7/npc spawn <名称> <world> <x> <y> <z> [类型] &8- 创建 NPC");
        sendMessage(sender, "&7/npc remove <名称> &8- 移除 NPC");
        sendMessage(sender, "&7/npc list &8- 列出所有 NPC");
        sendMessage(sender, "&7/npc chat <名称> <消息> &8- NPC 说话");
        sendMessage(sender, "&7/npc move <名称> <world> <x> <y> <z> [速度] &8- NPC 移动");
        sendMessage(sender, "&7/npc stop <名称> &8- 停止移动");
        sendMessage(sender, "&7/npc look <名称> <world> <x> <y> <z> &8- NPC 看向坐标");
        sendMessage(sender, "&7/npc equip <名称> <材质> [名称] [数量] &8- 给 NPC 装备");
        sendMessage(sender, "&7/npc tp <名称> <world> <x> <y> <z> &8- 传送 NPC");
        sendMessage(sender, "&7/npc info <名称> &8- 查看 NPC 信息");
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (args.length == 1) {
            return List.of("spawn", "remove", "list", "chat", "move", "stop", "look", "equip", "tp", "info", "help");
        }
        if (args.length == 2 && (args[0].equalsIgnoreCase("remove") || args[0].equalsIgnoreCase("chat")
                || args[0].equalsIgnoreCase("move") || args[0].equalsIgnoreCase("stop")
                || args[0].equalsIgnoreCase("look") || args[0].equalsIgnoreCase("equip")
                || args[0].equalsIgnoreCase("tp") || args[0].equalsIgnoreCase("info"))) {
            return plugin.getNpcManager().getAllNpcs().stream().map(AINpc::getName).toList();
        }
        return Collections.emptyList();
    }

    private void sendMessage(CommandSender sender, String message) {
        sender.sendMessage(Component.text(net.md_5.bungee.api.ChatColor.translateAlternateColorCodes('&', message)));
    }
}