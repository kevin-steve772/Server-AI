package com.serverai.npc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.serverai.Main;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.NotNull;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;

public class NPCFunctionHandler {

    private final Main plugin;
    private final NpcManager npcManager;
    private final Map<String, Function<JsonNode, CompletableFuture<String>>> functions = new ConcurrentHashMap<>();
    private final ObjectMapper mapper = new ObjectMapper();

    public NPCFunctionHandler(Main plugin, NpcManager npcManager) {
        this.plugin = plugin;
        this.npcManager = npcManager;
        registerFunctions();
    }

    public @NotNull List<ObjectNode> getToolDefinitions() {
        return new ArrayList<>(buildToolDefinitions().values());
    }

    public @NotNull CompletableFuture<String> executeFunction(@NotNull JsonNode call) {
        String name = call.get("name").asText();
        JsonNode args = call.get("arguments");
        return execute(name, args);
    }

    private void registerFunctions() {
        register("spawn_npc", this::spawnNpc);
        register("remove_npc", this::removeNpc);
        register("npc_chat", this::npcChat);
        register("npc_chat_to", this::npcChatTo);
        register("npc_move", this::npcMove);
        register("npc_stop", this::npcStop);
        register("npc_look_at", this::npcLookAt);
        register("npc_look_at_player", this::npcLookAtPlayer);
        register("npc_equip", this::npcEquip);
        register("npc_armor", this::npcArmor);
        register("npc_teleport", this::npcTeleport);
        register("npc_get_location", this::npcGetLocation);
        register("npc_list", this::npcList);
        register("get_online_players", this::getOnlinePlayers);
        register("get_player_location", this::getPlayerLocation);
    }

    private void register(String name, Function<JsonNode, CompletableFuture<String>> handler) {
        functions.put(name, handler);
    }

    public @NotNull Map<String, ObjectNode> getFunctionDefinitions() {
        return buildToolDefinitions();
    }

    private @NotNull Map<String, ObjectNode> buildToolDefinitions() {
        Map<String, ObjectNode> defs = new LinkedHashMap<>();

        defs.put("spawn_npc", createDef("spawn_npc", "在指定位置生成一个 AI NPC", Map.of(
                "name", createParam("string", "NPC 名称", true),
                "world", createParam("string", "世界名称", true),
                "x", createParam("number", "X 坐标", true),
                "y", createParam("number", "Y 坐标", true),
                "z", createParam("number", "Z 坐标", true),
                "type", createParam("string", "实体类型 (PLAYER, ZOMBIE, VILLAGER 等)", false, "PLAYER")
        )));

        defs.put("remove_npc", createDef("remove_npc", "移除指定名称的 NPC", Map.of(
                "name", createParam("string", "NPC 名称", true)
        )));

        defs.put("npc_chat", createDef("npc_chat", "让 NPC 在全服聊天", Map.of(
                "name", createParam("string", "NPC 名称", true),
                "message", createParam("string", "聊天内容", true)
        )));

        defs.put("npc_chat_to", createDef("npc_chat_to", "让 NPC 对特定玩家私聊", Map.of(
                "name", createParam("string", "NPC 名称", true),
                "player", createParam("string", "玩家名称", true),
                "message", createParam("string", "聊天内容", true)
        )));

        defs.put("npc_move", createDef("npc_move", "让 NPC 移动到指定坐标", Map.of(
                "name", createParam("string", "NPC 名称", true),
                "world", createParam("string", "世界名称", true),
                "x", createParam("number", "X 坐标", true),
                "y", createParam("number", "Y 坐标", true),
                "z", createParam("number", "Z 坐标", true),
                "speed", createParam("number", "移动速度 (默认 1.0)", false, 1.0)
        )));

        defs.put("npc_stop", createDef("npc_stop", "停止 NPC 移动", Map.of(
                "name", createParam("string", "NPC 名称", true)
        )));

        defs.put("npc_look_at", createDef("npc_look_at", "让 NPC 看向指定坐标", Map.of(
                "name", createParam("string", "NPC 名称", true),
                "world", createParam("string", "世界名称", true),
                "x", createParam("number", "X 坐标", true),
                "y", createParam("number", "Y 坐标", true),
                "z", createParam("number", "Z 坐标", true)
        )));

        defs.put("npc_look_at_player", createDef("npc_look_at_player", "让 NPC 看向指定玩家", Map.of(
                "name", createParam("string", "NPC 名称", true),
                "player", createParam("string", "玩家名称", true)
        )));

        defs.put("npc_equip", createDef("npc_equip", "给 NPC 手持物品", Map.of(
                "name", createParam("string", "NPC 名称", true),
                "material", createParam("string", "物品材质 (如 DIAMOND_SWORD)", true),
                "custom_name", createParam("string", "自定义名称", false),
                "amount", createParam("integer", "数量", false, 1)
        )));

        defs.put("npc_armor", createDef("npc_armor", "给 NPC 穿戴护甲", Map.of(
                "name", createParam("string", "NPC 名称", true),
                "helmet", createParam("string", "头盔材质", false),
                "chestplate", createParam("string", "胸甲材质", false),
                "leggings", createParam("string", "护腿材质", false),
                "boots", createParam("string", "靴子材质", false)
        )));

        defs.put("npc_teleport", createDef("npc_teleport", "瞬间传送 NPC", Map.of(
                "name", createParam("string", "NPC 名称", true),
                "world", createParam("string", "世界名称", true),
                "x", createParam("number", "X 坐标", true),
                "y", createParam("number", "Y 坐标", true),
                "z", createParam("number", "Z 坐标", true)
        )));

        defs.put("npc_get_location", createDef("npc_get_location", "获取 NPC 当前位置", Map.of(
                "name", createParam("string", "NPC 名称", true)
        )));

        defs.put("npc_list", createDef("npc_list", "列出所有 AI NPC", Map.of()));

        defs.put("get_online_players", createDef("get_online_players", "获取在线玩家列表", Map.of()));

        defs.put("get_player_location", createDef("get_player_location", "获取玩家位置", Map.of(
                "player", createParam("string", "玩家名称", true)
        )));

        return defs;
    }

    private ObjectNode createDef(String name, String desc, Map<String, ObjectNode> params) {
        ObjectNode def = mapper.createObjectNode();
        def.put("name", name);
        def.put("description", desc);
        ObjectNode paramsNode = mapper.createObjectNode();
        paramsNode.put("type", "object");
        ObjectNode props = mapper.createObjectNode();
        params.forEach(props::set);
        paramsNode.set("properties", props);
        ArrayNode required = mapper.createArrayNode();
        params.forEach((k, v) -> {
            if (v.has("required") && v.get("required").asBoolean()) {
                required.add(k);
            }
        });
        paramsNode.set("required", required);
        def.set("parameters", paramsNode);
        return def;
    }

    private ObjectNode createParam(String type, String desc, boolean required) {
        ObjectNode p = mapper.createObjectNode();
        p.put("type", type);
        p.put("description", desc);
        p.put("required", required);
        return p;
    }

    private ObjectNode createParam(String type, String desc, boolean required, Object defaultVal) {
        ObjectNode p = createParam(type, desc, required);
        if (defaultVal instanceof Number number) {
            p.put("default", number.doubleValue());
        } else if (defaultVal instanceof String string) {
            p.put("default", string);
        }
        return p;
    }

    public @NotNull CompletableFuture<String> execute(@NotNull String name, @NotNull JsonNode args) {
        Function<JsonNode, CompletableFuture<String>> fn = functions.get(name);
        if (fn == null) {
            return CompletableFuture.completedFuture("Unknown function: " + name);
        }
        try {
            return fn.apply(args);
        } catch (Exception e) {
            plugin.getLogger().severe("Function " + name + " error: " + e.getMessage());
            return CompletableFuture.completedFuture("Error: " + e.getMessage());
        }
    }

    private CompletableFuture<String> spawnNpc(JsonNode args) {
        String name = args.get("name").asText();
        String worldName = args.get("world").asText();
        double x = args.get("x").asDouble();
        double y = args.get("y").asDouble();
        double z = args.get("z").asDouble();
        String typeStr = args.has("type") ? args.get("type").asText() : "PLAYER";

        World world = Bukkit.getWorld(worldName);
        if (world == null) return CompletableFuture.completedFuture("World not found: " + worldName);

        EntityType type;
        try {
            type = EntityType.valueOf(typeStr.toUpperCase());
        } catch (IllegalArgumentException e) {
            type = EntityType.PLAYER;
        }

        Location loc = new Location(world, x, y, z);
        AINpc npc = npcManager.createNpc(name, loc, type);
        return CompletableFuture.completedFuture("NPC spawned: " + npc.getName() + " (" + npc.getId() + ")");
    }

    private CompletableFuture<String> removeNpc(JsonNode args) {
        String name = args.get("name").asText();
        AINpc npc = npcManager.getNpcByName(name);
        if (npc == null) return CompletableFuture.completedFuture("NPC not found: " + name);
        npc.destroy();
        return CompletableFuture.completedFuture("NPC removed: " + name);
    }

    private CompletableFuture<String> npcChat(JsonNode args) {
        String name = args.get("name").asText();
        String msg = args.get("message").asText();
        AINpc npc = npcManager.getNpcByName(name);
        if (npc == null) return CompletableFuture.completedFuture("NPC not found: " + name);
        npc.chat(msg);
        return CompletableFuture.completedFuture("NPC " + name + " said: " + msg);
    }

    private CompletableFuture<String> npcChatTo(JsonNode args) {
        String name = args.get("name").asText();
        String playerName = args.get("player").asText();
        String msg = args.get("message").asText();
        AINpc npc = npcManager.getNpcByName(name);
        Player player = Bukkit.getPlayerExact(playerName);
        if (npc == null) return CompletableFuture.completedFuture("NPC not found: " + name);
        if (player == null) return CompletableFuture.completedFuture("Player not online: " + playerName);
        npc.chatTo(player, msg);
        return CompletableFuture.completedFuture("NPC " + name + " whispered to " + playerName);
    }

    private CompletableFuture<String> npcMove(JsonNode args) {
        String name = args.get("name").asText();
        String worldName = args.get("world").asText();
        double x = args.get("x").asDouble();
        double y = args.get("y").asDouble();
        double z = args.get("z").asDouble();
        double speed = args.has("speed") ? args.get("speed").asDouble() : 1.0;

        AINpc npc = npcManager.getNpcByName(name);
        World world = Bukkit.getWorld(worldName);
        if (npc == null) return CompletableFuture.completedFuture("NPC not found: " + name);
        if (world == null) return CompletableFuture.completedFuture("World not found: " + worldName);

        Location target = new Location(world, x, y, z);
        npc.moveTo(target, speed);
        return CompletableFuture.completedFuture("NPC " + name + " moving to " + x + "," + y + "," + z);
    }

    private CompletableFuture<String> npcStop(JsonNode args) {
        String name = args.get("name").asText();
        AINpc npc = npcManager.getNpcByName(name);
        if (npc == null) return CompletableFuture.completedFuture("NPC not found: " + name);
        npc.stopMoving();
        return CompletableFuture.completedFuture("NPC " + name + " stopped moving");
    }

    private CompletableFuture<String> npcLookAt(JsonNode args) {
        String name = args.get("name").asText();
        String worldName = args.get("world").asText();
        double x = args.get("x").asDouble();
        double y = args.get("y").asDouble();
        double z = args.get("z").asDouble();

        AINpc npc = npcManager.getNpcByName(name);
        World world = Bukkit.getWorld(worldName);
        if (npc == null) return CompletableFuture.completedFuture("NPC not found: " + name);
        if (world == null) return CompletableFuture.completedFuture("World not found: " + worldName);

        npc.lookAt(new Location(world, x, y, z));
        return CompletableFuture.completedFuture("NPC " + name + " looking at " + x + "," + y + "," + z);
    }

    private CompletableFuture<String> npcLookAtPlayer(JsonNode args) {
        String name = args.get("name").asText();
        String playerName = args.get("player").asText();
        AINpc npc = npcManager.getNpcByName(name);
        Player player = Bukkit.getPlayerExact(playerName);
        if (npc == null) return CompletableFuture.completedFuture("NPC not found: " + name);
        if (player == null) return CompletableFuture.completedFuture("Player not online: " + playerName);
        npc.lookAt(player);
        return CompletableFuture.completedFuture("NPC " + name + " looking at " + playerName);
    }

    private CompletableFuture<String> npcEquip(JsonNode args) {
        String name = args.get("name").asText();
        String material = args.get("material").asText();
        String customName = args.has("custom_name") ? args.get("custom_name").asText() : null;
        int amount = args.has("amount") ? args.get("amount").asInt() : 1;

        AINpc npc = npcManager.getNpcByName(name);
        if (npc == null) return CompletableFuture.completedFuture("NPC not found: " + name);

        try {
            org.bukkit.Material mat = org.bukkit.Material.valueOf(material.toUpperCase());
            ItemStack item = new ItemStack(mat, amount);
            if (customName != null) {
                ItemMeta meta = item.getItemMeta();
                meta.setDisplayName(customName);
                item.setItemMeta(meta);
            }
            npc.setEquipment(item);
            return CompletableFuture.completedFuture("NPC " + name + " equipped " + material);
        } catch (IllegalArgumentException e) {
            return CompletableFuture.completedFuture("Invalid material: " + material);
        }
    }

    private CompletableFuture<String> npcArmor(JsonNode args) {
        String name = args.get("name").asText();
        AINpc npc = npcManager.getNpcByName(name);
        if (npc == null) return CompletableFuture.completedFuture("NPC not found: " + name);

        ItemStack helmet = parseItem(args, "helmet");
        ItemStack chest = parseItem(args, "chestplate");
        ItemStack legs = parseItem(args, "leggings");
        ItemStack boots = parseItem(args, "boots");

        npc.setArmor(helmet, chest, legs, boots);
        return CompletableFuture.completedFuture("NPC " + name + " armor updated");
    }

    private ItemStack parseItem(JsonNode args, String field) {
        if (!args.has(field) || args.get(field).isNull()) return new ItemStack(org.bukkit.Material.AIR);
        try {
            return new ItemStack(org.bukkit.Material.valueOf(args.get(field).asText().toUpperCase()));
        } catch (IllegalArgumentException e) {
            return new ItemStack(org.bukkit.Material.AIR);
        }
    }

    private CompletableFuture<String> npcTeleport(JsonNode args) {
        String name = args.get("name").asText();
        String worldName = args.get("world").asText();
        double x = args.get("x").asDouble();
        double y = args.get("y").asDouble();
        double z = args.get("z").asDouble();

        AINpc npc = npcManager.getNpcByName(name);
        World world = Bukkit.getWorld(worldName);
        if (npc == null) return CompletableFuture.completedFuture("NPC not found: " + name);
        if (world == null) return CompletableFuture.completedFuture("World not found: " + worldName);

        npc.getEntity().teleport(new Location(world, x, y, z));
        return CompletableFuture.completedFuture("NPC " + name + " teleported to " + x + "," + y + "," + z);
    }

    private CompletableFuture<String> npcGetLocation(JsonNode args) {
        String name = args.get("name").asText();
        AINpc npc = npcManager.getNpcByName(name);
        if (npc == null) return CompletableFuture.completedFuture("NPC not found: " + name);
        Location loc = npc.getLocation();
        return CompletableFuture.completedFuture(loc.getWorld().getName() + "," + loc.getX() + "," + loc.getY() + "," + loc.getZ());
    }

    private CompletableFuture<String> npcList(JsonNode args) {
        Collection<AINpc> npcs = npcManager.getAllNpcs();
        if (npcs.isEmpty()) return CompletableFuture.completedFuture("No NPCs spawned");
        StringBuilder sb = new StringBuilder("AI NPCs:\n");
        for (AINpc npc : npcs) {
            Location l = npc.getLocation();
            sb.append(" - ").append(npc.getName()).append(" (").append(npc.getId()).append(") at ")
              .append(l.getWorld().getName()).append(",").append((int)l.getX()).append(",").append((int)l.getY()).append(",").append((int)l.getZ())
              .append(" [").append(npc.getCurrentAction()).append("]\n");
        }
        return CompletableFuture.completedFuture(sb.toString());
    }

    private CompletableFuture<String> getOnlinePlayers(JsonNode args) {
        List<Player> players = new ArrayList<>(Bukkit.getOnlinePlayers());
        if (players.isEmpty()) return CompletableFuture.completedFuture("No players online");
        StringBuilder sb = new StringBuilder("Online players:\n");
        for (Player p : players) {
            Location l = p.getLocation();
            sb.append(" - ").append(p.getName()).append(" at ").append(l.getWorld().getName()).append(",").append((int)l.getX()).append(",").append((int)l.getY()).append(",").append((int)l.getZ()).append("\n");
        }
        return CompletableFuture.completedFuture(sb.toString());
    }

    private CompletableFuture<String> getPlayerLocation(JsonNode args) {
        String playerName = args.get("player").asText();
        Player player = Bukkit.getPlayerExact(playerName);
        if (player == null) return CompletableFuture.completedFuture("Player not online: " + playerName);
        Location l = player.getLocation();
        return CompletableFuture.completedFuture(l.getWorld().getName() + "," + l.getX() + "," + l.getY() + "," + l.getZ());
    }
}