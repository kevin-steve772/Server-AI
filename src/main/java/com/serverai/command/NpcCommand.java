package com.serverai.command;

import com.serverai.Main;
import com.serverai.npc.NpcManager;
import com.serverai.npc.NpcManager.MoveResult;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

public final class NpcCommand implements TabExecutor {

    private static final Component USAGE = Component.text(
            "用法: /ainpc spawn | remove | say <消息> | move <世界> <x> <y> <z> [速度] | come | stop | info | equip <材质> [名称] [数量] | armor [头盔] [胸甲] [护腿] [靴子] | skin <玩家名> | tp <世界> <x> <y> <z> | look <coordinates|player> <世界/玩家名> [x] [y] [z]",
            NamedTextColor.YELLOW);

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

        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "spawn" -> spawn(sender);
            case "remove" -> {
                plugin.getNpcManager().despawn();
                sender.sendMessage(Component.text("NPC已移除", NamedTextColor.GREEN));
            }
            case "say" -> say(sender, args);
            case "move" -> move(sender, args);
            case "come" -> come(sender);
            case "stop" -> completeBoolean(sender, plugin.getNpcManager().stopMovement(),
                    "NPC已停止移动", "NPC未生成");
            case "info" -> info(sender);
            case "equip" -> equip(sender, args);
            case "armor" -> armor(sender, args);
            case "skin" -> skin(sender, args);
            case "tp", "teleport" -> teleport(sender, args);
            case "look" -> look(sender, args);
            default -> sender.sendMessage(USAGE);
        }
        return true;
    }

    private void spawn(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("只有玩家才能执行此命令", NamedTextColor.RED));
            return;
        }
        if (plugin.getNpcManager().spawn(player.getLocation())) {
            sender.sendMessage(Component.text("NPC已生成在当前位置", NamedTextColor.GREEN));
        } else {
            sender.sendMessage(Component.text("NPC生成失败", NamedTextColor.RED));
        }
    }

    private void say(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(Component.text("用法: /ainpc say <消息>", NamedTextColor.YELLOW));
            return;
        }
        if (!requireNpc(sender)) {
            return;
        }
        String message = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
        plugin.getNpcManager().say(message);
    }

    private void move(CommandSender sender, String[] args) {
        if (args.length < 5 || args.length > 6) {
            sender.sendMessage(Component.text(
                    "用法: /ainpc move <世界> <x> <y> <z> [速度]", NamedTextColor.YELLOW));
            return;
        }
        if (!requireNpc(sender)) {
            return;
        }

        World world = Bukkit.getWorld(args[1]);
        if (world == null) {
            sender.sendMessage(Component.text("找不到世界: " + args[1], NamedTextColor.RED));
            return;
        }

        try {
            double x = finiteDouble(args[2]);
            double y = finiteDouble(args[3]);
            double z = finiteDouble(args[4]);
            double speed = args.length == 6
                    ? finiteDouble(args[5]) : plugin.getNpcManager().getDefaultSpeed();
            Location target = new Location(world, x, y, z);
            completeMovement(sender, plugin.getNpcManager().moveTo(target, speed));
        } catch (NumberFormatException exception) {
            sender.sendMessage(Component.text("坐标和速度必须是有效数字", NamedTextColor.RED));
        }
    }

    private void come(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("只有玩家才能使用 /npc come", NamedTextColor.RED));
            return;
        }
        if (!requireNpc(sender)) {
            return;
        }
        completeMovement(sender, plugin.getNpcManager().moveTo(
                player.getLocation(), plugin.getNpcManager().getDefaultSpeed()));
    }

    private void info(CommandSender sender) {
        plugin.getNpcManager().getState().whenComplete((state, error) ->
                plugin.runForSender(sender, () -> {
                    if (error != null) {
                        sender.sendMessage(Component.text(
                                "读取NPC状态失败: " + safeMessage(error), NamedTextColor.RED));
                    } else if (state == null) {
                        sender.sendMessage(plugin.getMessages().get(
                                "messages.npc-not-spawned", "&c请先生成 NPC。"));
                    } else {
                        String status = String.format(Locale.ROOT,
                                "NPC位置: %s %.1f %.1f %.1f，状态: %s",
                                state.world(), state.x(), state.y(), state.z(),
                                state.moving() ? "移动中" : "空闲");
                        sender.sendMessage(Component.text(status, NamedTextColor.AQUA));
                    }
                }));
    }

    private void equip(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(Component.text("用法: /npc equip <材质> [名称] [数量]", NamedTextColor.YELLOW));
            return;
        }
        if (!requireNpc(sender)) {
            return;
        }
        try {
            Material material = Material.valueOf(args[1].toUpperCase());
            String customName = args.length > 2 ? args[2] : null;
            int amount = args.length > 3 ? Integer.parseInt(args[3]) : 1;
            ItemStack item = new ItemStack(material, amount);
            if (customName != null && !customName.isBlank()) {
                ItemMeta meta = item.getItemMeta();
                meta.displayName(plugin.getMessages().markdown(customName));
                item.setItemMeta(meta);
            }
            plugin.getNpcManager().equip(item);
            sender.sendMessage(Component.text("NPC已装备: " + material, NamedTextColor.GREEN));
        } catch (IllegalArgumentException e) {
            sender.sendMessage(Component.text("无效材质: " + args[1], NamedTextColor.RED));
        }
    }

    private void armor(CommandSender sender, String[] args) {
        if (!requireNpc(sender)) {
            return;
        }
        ItemStack helmet = args.length > 1 && !args[1].isBlank() ? parseItem(args[1]) : null;
        ItemStack chest = args.length > 2 && !args[2].isBlank() ? parseItem(args[2]) : null;
        ItemStack legs = args.length > 3 && !args[3].isBlank() ? parseItem(args[3]) : null;
        ItemStack boots = args.length > 4 && !args[4].isBlank() ? parseItem(args[4]) : null;

        if (helmet == null && chest == null && legs == null && boots == null) {
            sender.sendMessage(Component.text("用法: /npc armor [头盔] [胸甲] [护腿] [靴子]", NamedTextColor.YELLOW));
            return;
        }
        plugin.getNpcManager().setArmor(helmet, chest, legs, boots);
        sender.sendMessage(Component.text("NPC护甲已更新", NamedTextColor.GREEN));
    }

    private ItemStack parseItem(String materialName) {
        try {
            return new ItemStack(Material.valueOf(materialName.toUpperCase()));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private void skin(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(Component.text("用法: /ainpc skin <玩家名>", NamedTextColor.YELLOW));
            return;
        }
        if (!requireNpc(sender)) {
            return;
        }
        String skinName = args[1];
        plugin.getNpcManager().getEntity().getOrAddTrait(net.citizensnpcs.api.trait.trait.Skin.class).setSkinName(skinName);
        sender.sendMessage(Component.text("NPC皮肤已设置为: " + skinName, NamedTextColor.GREEN));
    }

    private void teleport(CommandSender sender, String[] args) {
        if (args.length < 5) {
            sender.sendMessage(Component.text("用法: /ainpc tp <世界> <x> <y> <z>", NamedTextColor.YELLOW));
            return;
        }
        if (!requireNpc(sender)) {
            return;
        }
        World world = Bukkit.getWorld(args[1]);
        if (world == null) {
            sender.sendMessage(Component.text("找不到世界: " + args[1], NamedTextColor.RED));
            return;
        }
        try {
            double x = finiteDouble(args[2]);
            double y = finiteDouble(args[3]);
            double z = finiteDouble(args[4]);
            Location loc = new Location(world, x, y, z);
            plugin.getNpcManager().getEntity().teleport(loc);
            sender.sendMessage(Component.text("NPC已传送至 " + world.getName() + " " + x + " " + y + " " + z, NamedTextColor.GREEN));
        } catch (NumberFormatException e) {
            sender.sendMessage(Component.text("坐标必须是有效数字", NamedTextColor.RED));
        }
    }

    private void look(CommandSender sender, String[] args) {
        if (args.length < 3) {
            sender.sendMessage(Component.text("用法: /ainpc look <coordinates|player> <世界/玩家名> [x] [y] [z]", NamedTextColor.YELLOW));
            return;
        }
        if (!requireNpc(sender)) {
            return;
        }
        String targetType = args[1].toLowerCase(Locale.ROOT);
        if ("coordinates".equals(targetType) || "coord".equals(targetType)) {
            if (args.length < 6) {
                sender.sendMessage(Component.text("用法: /npc look coordinates <世界> <x> <y> <z>", NamedTextColor.YELLOW));
                return;
            }
            World world = Bukkit.getWorld(args[2]);
            if (world == null) {
                sender.sendMessage(Component.text("找不到世界: " + args[2], NamedTextColor.RED));
                return;
            }
            try {
                double x = finiteDouble(args[3]);
                double y = finiteDouble(args[4]);
                double z = finiteDouble(args[5]);
                plugin.getNpcManager().getEntity().faceLocation(new Location(world, x, y, z));
                sender.sendMessage(Component.text("NPC正在看向坐标: " + x + " " + y + " " + z, NamedTextColor.GREEN));
            } catch (NumberFormatException e) {
                sender.sendMessage(Component.text("坐标必须是有效数字", NamedTextColor.RED));
            }
        } else if ("player".equals(targetType)) {
            String playerName = args[2];
            Player player = Bukkit.getPlayerExact(playerName);
            if (player == null || !player.isOnline()) {
                sender.sendMessage(Component.text("玩家不在线: " + playerName, NamedTextColor.RED));
                return;
            }
            plugin.getNpcManager().getEntity().faceEntity(player);
            sender.sendMessage(Component.text("NPC正在看向玩家: " + playerName, NamedTextColor.GREEN));
        } else {
            sender.sendMessage(Component.text("目标类型必须是 coordinates 或 player", NamedTextColor.RED));
        }
    }

    private void completeBoolean(CommandSender sender, CompletableFuture<Boolean> future,
                                 String successMessage, String failureMessage) {
        future.whenComplete((success, error) -> plugin.runForSender(sender, () -> {
            if (error != null) {
                sender.sendMessage(Component.text(
                        failureMessage + ": " + safeMessage(error), NamedTextColor.RED));
            } else if (Boolean.TRUE.equals(success)) {
                sender.sendMessage(Component.text(successMessage, NamedTextColor.GREEN));
            } else {
                sender.sendMessage(Component.text(failureMessage, NamedTextColor.RED));
            }
        }));
    }

    private void completeMovement(CommandSender sender, CompletableFuture<MoveResult> future) {
        future.whenComplete((result, error) -> plugin.runForSender(sender, () -> {
            if (error != null) {
                sender.sendMessage(Component.text(
                        "NPC移动失败: " + safeMessage(error), NamedTextColor.RED));
                return;
            }

            switch (result) {
                case STARTED -> sender.sendMessage(Component.text(
                        "NPC开始移动", NamedTextColor.GREEN));
                case ALREADY_THERE -> sender.sendMessage(Component.text(
                        "NPC已在目标位置附近", NamedTextColor.GREEN));
                case NOT_SPAWNED -> sender.sendMessage(plugin.getMessages().get(
                        "messages.npc-not-spawned", "&c请先生成 NPC。"));
                case DIFFERENT_WORLD -> sender.sendMessage(Component.text(
                        "NPC和目标不在同一个世界", NamedTextColor.RED));
                case TOO_FAR -> sender.sendMessage(Component.text(String.format(Locale.ROOT,
                        "目标超过最大移动距离 %.0f 格", plugin.getNpcManager().getMaxMoveDistance()),
                        NamedTextColor.RED));
                case INVALID_LOCATION -> sender.sendMessage(Component.text(
                        "目标坐标无效", NamedTextColor.RED));
                case INVALID_SPEED -> sender.sendMessage(Component.text(
                        "速度必须在 0.1 到 2.0 之间", NamedTextColor.RED));
                case NO_PATH -> sender.sendMessage(Component.text(
                        "找不到可行走路径，请选择地面上的可达坐标", NamedTextColor.RED));
            }
        }));
    }

    private boolean requireNpc(CommandSender sender) {
        if (plugin.getNpcManager().isSpawned()) {
            return true;
        }
        sender.sendMessage(plugin.getMessages().get(
                "messages.npc-not-spawned", "&c请先生成 NPC。"));
        return false;
    }

    private static double finiteDouble(String value) {
        double number = Double.parseDouble(value);
        if (!Double.isFinite(number)) {
            throw new NumberFormatException("Number must be finite");
        }
        return number;
    }

    private static String safeMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return message == null || message.isBlank() ? "未知错误" : message;
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender,
                                                 @NotNull Command command,
                                                 @NotNull String label,
                                                 @NotNull String[] args) {
        if (args.length == 1) {
            return List.of("spawn", "remove", "say", "move", "come", "stop", "info", "equip", "armor", "skin", "tp", "teleport", "look");
        }
        if (args.length == 2 && (args[0].equalsIgnoreCase("move") || args[0].equalsIgnoreCase("tp") || args[0].equalsIgnoreCase("teleport"))) {
            return Bukkit.getWorlds().stream().map(World::getName).toList();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("look")) {
            return List.of("coordinates", "player");
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("look") && args[1].equalsIgnoreCase("player")) {
            return Bukkit.getOnlinePlayers().stream().map(Player::getName).toList();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("skin")) {
            return Bukkit.getOnlinePlayers().stream().map(Player::getName).toList();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("equip")) {
            return List.of("DIAMOND_SWORD", "NETHERITE_SWORD", "GOLDEN_APPLE", "SHIELD");
        }
        if (args.length >= 2 && args[0].equalsIgnoreCase("armor")) {
            return List.of("DIAMOND_HELMET", "DIAMOND_CHESTPLATE", "DIAMOND_LEGGINGS", "DIAMOND_BOOTS", "NETHERITE_HELMET", "NETHERITE_CHESTPLATE", "NETHERITE_LEGGINGS", "NETHERITE_BOOTS");
        }
        return Collections.emptyList();
    }
}