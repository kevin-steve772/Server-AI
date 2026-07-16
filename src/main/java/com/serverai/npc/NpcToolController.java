package com.serverai.npc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.serverai.Main;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public final class NpcToolController {

    private final Main plugin;
    private final NpcManager npcManager;
    private final ObjectMapper mapper = new ObjectMapper();
    private final List<ObjectNode> definitions;

    public NpcToolController(Main plugin, NpcManager npcManager) {
        this.plugin = plugin;
        this.npcManager = npcManager;
        definitions = createDefinitions();
    }

    public List<ObjectNode> getDefinitions() {
        return definitions;
    }

    public CompletableFuture<String> execute(JsonNode invocation) {
        CompletableFuture<String> result;
        try {
            String name = requiredText(invocation, "name");
            JsonNode arguments = invocation.path("arguments");
            result = switch (name) {
                case "npc_move" -> moveToCoordinates(arguments);
                case "npc_move_to_player" -> moveToPlayer(arguments);
                case "npc_stop" -> stop();
                case "npc_say" -> say(arguments);
                case "npc_get_location" -> getLocation();
                case "npc_equip" -> equip(arguments);
                case "npc_armor" -> armor(arguments);
                case "npc_skin" -> setSkin(arguments);
                case "npc_teleport" -> teleport(arguments);
                case "npc_look_at" -> lookAt(arguments);
                default -> CompletableFuture.completedFuture("Error: unknown NPC tool " + name);
            };
        } catch (IllegalArgumentException exception) {
            return CompletableFuture.completedFuture("Error: " + exception.getMessage());
        }
        return result.completeOnTimeout("Error: NPC action timed out", 5, TimeUnit.SECONDS)
                .handle((value, error) -> error == null
                        ? value : "Error: " + safeMessage(error));
    }

    private CompletableFuture<String> moveToCoordinates(JsonNode arguments) {
        String worldName = requiredText(arguments, "world");
        double x = requiredNumber(arguments, "x");
        double y = requiredNumber(arguments, "y");
        double z = requiredNumber(arguments, "z");
        double speed = optionalNumber(arguments, "speed", npcManager.getDefaultSpeed());

        CompletableFuture<String> result = new CompletableFuture<>();
        if (!plugin.isEnabled()) {
            result.complete("Error: plugin is disabled");
            return result;
        }
        boolean scheduled = plugin.runGlobal(() -> {
            World world = Bukkit.getWorld(worldName);
            if (world == null) {
                result.complete("Error: world not found: " + worldName);
                return;
            }
            completeMove(result, npcManager.moveTo(new Location(world, x, y, z), speed));
        });
        if (!scheduled) {
            result.complete("Error: plugin is disabled");
        }
        return result;
    }

    private CompletableFuture<String> moveToPlayer(JsonNode arguments) {
        String playerName = requiredText(arguments, "player");
        double speed = optionalNumber(arguments, "speed", npcManager.getDefaultSpeed());
        CompletableFuture<String> result = new CompletableFuture<>();

        if (!plugin.isEnabled()) {
            result.complete("Error: plugin is disabled");
            return result;
        }
        boolean globalScheduled = plugin.runGlobal(() -> {
            Player player = Bukkit.getPlayerExact(playerName);
            if (player == null || !player.isOnline()) {
                result.complete("Error: player is not online: " + playerName);
                return;
            }
            boolean scheduled = player.getScheduler().execute(plugin, () ->
                            completeMove(result, npcManager.moveTo(player.getLocation(), speed)),
                    () -> result.complete("Error: player left the server"), 1L);
            if (!scheduled) {
                result.complete("Error: player location is unavailable");
            }
        });
        if (!globalScheduled) {
            result.complete("Error: plugin is disabled");
        }
        return result;
    }

    private CompletableFuture<String> stop() {
        return npcManager.stopMovement().thenApply(stopped -> stopped
                ? "NPC movement stopped"
                : "Error: NPC is not spawned");
    }

    private CompletableFuture<String> say(JsonNode arguments) {
        String message = requiredText(arguments, "message");
        if (!npcManager.isSpawned()) {
            return CompletableFuture.completedFuture("Error: NPC is not spawned");
        }
        npcManager.say(message);
        return CompletableFuture.completedFuture("NPC said the message");
    }

    private CompletableFuture<String> getLocation() {
        return npcManager.getState().thenApply(state -> {
            if (state == null) {
                return "Error: NPC is not spawned";
            }
            return String.format(Locale.ROOT,
                    "NPC is at world=%s x=%.2f y=%.2f z=%.2f and is %s",
                    state.world(), state.x(), state.y(), state.z(),
                    state.moving() ? "moving" : "idle");
        });
    }

    private CompletableFuture<String> equip(JsonNode arguments) {
        if (!npcManager.isSpawned()) {
            return CompletableFuture.completedFuture("Error: NPC is not spawned");
        }
        String material = requiredText(arguments, "material");
        String customName = arguments.has("custom_name") ? arguments.get("custom_name").asText() : null;
        int amount = arguments.has("amount") ? arguments.get("amount").asInt() : 1;

        try {
            Material mat = Material.valueOf(material.toUpperCase());
            ItemStack item = new ItemStack(mat, amount);
            if (customName != null && !customName.isBlank()) {
                ItemMeta meta = item.getItemMeta();
                meta.displayName(plugin.getMessages().markdown(customName));
                item.setItemMeta(meta);
            }
            npcManager.equip(item);
            return CompletableFuture.completedFuture("NPC equipped with " + material);
        } catch (IllegalArgumentException e) {
            return CompletableFuture.completedFuture("Error: invalid material: " + material);
        }
    }

    private CompletableFuture<String> armor(JsonNode arguments) {
        if (!npcManager.isSpawned()) {
            return CompletableFuture.completedFuture("Error: NPC is not spawned");
        }
        ItemStack helmet = parseItem(arguments, "helmet");
        ItemStack chest = parseItem(arguments, "chestplate");
        ItemStack legs = parseItem(arguments, "leggings");
        ItemStack boots = parseItem(arguments, "boots");

        if (helmet == null && chest == null && legs == null && boots == null) {
            return CompletableFuture.completedFuture("Error: no armor items specified");
        }
        npcManager.setArmor(helmet, chest, legs, boots);
        return CompletableFuture.completedFuture("NPC armor updated");
    }

    private ItemStack parseItem(JsonNode arguments, String field) {
        if (!arguments.has(field) || arguments.get(field).isNull()) {
            return null;
        }
        String material = arguments.get(field).asText();
        try {
            return new ItemStack(Material.valueOf(material.toUpperCase()));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private CompletableFuture<String> setSkin(JsonNode arguments) {
        if (!npcManager.isSpawned()) {
            return CompletableFuture.completedFuture("Error: NPC is not spawned");
        }
        String skinName = requiredText(arguments, "skin");
        npcManager.getEntity().getOrAddTrait(net.citizensnpcs.api.trait.trait.Skin.class).setSkinName(skinName);
        return CompletableFuture.completedFuture("NPC skin set to " + skinName);
    }

    private CompletableFuture<String> teleport(JsonNode arguments) {
        String worldName = requiredText(arguments, "world");
        double x = requiredNumber(arguments, "x");
        double y = requiredNumber(arguments, "y");
        double z = requiredNumber(arguments, "z");

        CompletableFuture<String> result = new CompletableFuture<>();
        if (!plugin.isEnabled()) {
            result.complete("Error: plugin is disabled");
            return result;
        }
        boolean scheduled = plugin.runGlobal(() -> {
            World world = Bukkit.getWorld(worldName);
            if (world == null) {
                result.complete("Error: world not found: " + worldName);
                return;
            }
            Location loc = new Location(world, x, y, z);
            npcManager.getEntity().teleport(loc);
            result.complete("NPC teleported to " + worldName + " " + x + " " + y + " " + z);
        });
        if (!scheduled) {
            result.complete("Error: plugin is disabled");
        }
        return result;
    }

    private CompletableFuture<String> lookAt(JsonNode arguments) {
        String targetType = requiredText(arguments, "target_type"); // "coordinates" or "player"
        CompletableFuture<String> result = new CompletableFuture<>();

        if (!plugin.isEnabled()) {
            result.complete("Error: plugin is disabled");
            return result;
        }

        if ("coordinates".equals(targetType)) {
            String worldName = requiredText(arguments, "world");
            double x = requiredNumber(arguments, "x");
            double y = requiredNumber(arguments, "y");
            double z = requiredNumber(arguments, "z");

            plugin.runGlobal(() -> {
                World world = Bukkit.getWorld(worldName);
                if (world == null) {
                    result.complete("Error: world not found: " + worldName);
                    return;
                }
                Location loc = new Location(world, x, y, z);
                npcManager.getEntity().faceLocation(loc);
                result.complete("NPC looking at " + worldName + " " + x + " " + y + " " + z);
            });
        } else if ("player".equals(targetType)) {
            String playerName = requiredText(arguments, "player");
            plugin.runGlobal(() -> {
                Player player = Bukkit.getPlayerExact(playerName);
                if (player == null || !player.isOnline()) {
                    result.complete("Error: player not online: " + playerName);
                    return;
                }
                npcManager.getEntity().faceEntity(player);
                result.complete("NPC looking at player " + playerName);
            });
        } else {
            result.complete("Error: invalid target_type, must be 'coordinates' or 'player'");
        }
        return result;
    }

    private void completeMove(CompletableFuture<String> result,
                              CompletableFuture<NpcManager.MoveResult> movement) {
        movement.whenComplete((moveResult, error) -> {
            if (error != null) {
                result.completeExceptionally(error);
            } else {
                result.complete(switch (moveResult) {
                    case STARTED -> "NPC started moving";
                    case ALREADY_THERE -> "NPC is already near the destination";
                    case NOT_SPAWNED -> "Error: NPC is not spawned";
                    case DIFFERENT_WORLD -> "Error: NPC and destination are in different worlds";
                    case TOO_FAR -> String.format(Locale.ROOT,
                            "Error: destination exceeds the maximum movement distance of %.0f blocks",
                            npcManager.getMaxMoveDistance());
                    case INVALID_LOCATION -> "Error: destination coordinates are invalid";
                    case INVALID_SPEED -> "Error: speed must be between 0.1 and 2.0";
                    case NO_PATH -> "Error: no walkable path to the destination";
                });
            }
        });
    }

    private List<ObjectNode> createDefinitions() {
        return List.of(
                tool("npc_move",
                        "Move the spawned NPC to coordinates in its current world.",
                        properties(
                                property("world", "string", "Exact Minecraft world name"),
                                property("x", "number", "Target X coordinate"),
                                property("y", "number", "Target Y coordinate"),
                                property("z", "number", "Target Z coordinate"),
                                boundedNumberProperty("speed",
                                        "Optional speed from 0.1 to 2.0", 0.1, 2.0)),
                        "world", "x", "y", "z"),
                tool("npc_move_to_player",
                        "Move the spawned NPC to an online player. Prefer this when asked to come or follow once.",
                        properties(
                                property("player", "string", "Exact online player name"),
                                boundedNumberProperty("speed",
                                        "Optional speed from 0.1 to 2.0", 0.1, 2.0)),
                        "player"),
                tool("npc_stop", "Stop the spawned NPC immediately.",
                        mapper.createObjectNode()),
                tool("npc_say", "Make the spawned NPC broadcast a short message.",
                        properties(property("message", "string", "Message for the NPC to say")),
                        "message"),
                tool("npc_get_location", "Get the spawned NPC location and movement state.",
                        mapper.createObjectNode()),
                tool("npc_equip", "Equip an item in the NPC's hand.",
                        properties(
                                property("material", "string", "Minecraft material name (e.g. DIAMOND_SWORD)"),
                                property("custom_name", "string", "Optional custom display name"),
                                property("amount", "integer", "Optional item count (default 1)")),
                        "material"),
                tool("npc_armor", "Set armor on the NPC.",
                        properties(
                                property("helmet", "string", "Helmet material (e.g. DIAMOND_HELMET)"),
                                property("chestplate", "string", "Chestplate material"),
                                property("leggings", "string", "Leggings material"),
                                property("boots", "string", "Boots material"))),
                tool("npc_skin", "Change the NPC's skin by player name.",
                        properties(property("skin", "string", "Player name for skin")),
                        "skin"),
                tool("npc_teleport", "Teleport the NPC instantly to coordinates.",
                        properties(
                                property("world", "string", "World name"),
                                property("x", "number", "Target X coordinate"),
                                property("y", "number", "Target Y coordinate"),
                                property("z", "number", "Target Z coordinate")),
                        "world", "x", "y", "z"),
                tool("npc_look_at", "Make the NPC look at coordinates or a player.",
                        properties(
                                property("target_type", "string", "Either 'coordinates' or 'player'"),
                                property("world", "string", "World name (for coordinates)"),
                                property("x", "number", "Target X (for coordinates)"),
                                property("y", "number", "Target Y (for coordinates)"),
                                property("z", "number", "Target Z (for coordinates)"),
                                property("player", "string", "Player name (for player)")),
                        "target_type"));
    }

    private ObjectNode tool(String name, String description, ObjectNode properties,
                            String... required) {
        ObjectNode parameters = mapper.createObjectNode();
        parameters.put("type", "object");
        parameters.set("properties", properties);
        parameters.put("additionalProperties", false);
        if (required.length > 0) {
            ArrayNode requiredFields = mapper.createArrayNode();
            for (String field : required) {
                requiredFields.add(field);
            }
            parameters.set("required", requiredFields);
        }

        ObjectNode function = mapper.createObjectNode();
        function.put("name", name);
        function.put("description", description);
        function.set("parameters", parameters);

        ObjectNode definition = mapper.createObjectNode();
        definition.put("type", "function");
        definition.set("function", function);
        return definition;
    }

    private ObjectNode properties(ObjectNode... fields) {
        ObjectNode properties = mapper.createObjectNode();
        for (ObjectNode field : fields) {
            String name = field.remove("name").asText();
            properties.set(name, field);
        }
        return properties;
    }

    private ObjectNode property(String name, String type, String description) {
        ObjectNode property = mapper.createObjectNode();
        property.put("name", name);
        property.put("type", type);
        property.put("description", description);
        return property;
    }

    private ObjectNode boundedNumberProperty(String name, String description,
                                             double minimum, double maximum) {
        ObjectNode property = property(name, "number", description);
        property.put("minimum", minimum);
        property.put("maximum", maximum);
        return property;
    }

    private static String requiredText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            throw new IllegalArgumentException("missing text argument: " + field);
        }
        return value.asText().trim();
    }

    private static double requiredNumber(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isNumber() || !Double.isFinite(value.asDouble())) {
            throw new IllegalArgumentException("missing numeric argument: " + field);
        }
        return value.asDouble();
    }

    private static double optionalNumber(JsonNode node, String field, double fallback) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return fallback;
        }
        if (!value.isNumber() || !Double.isFinite(value.asDouble())) {
            throw new IllegalArgumentException("invalid numeric argument: " + field);
        }
        return value.asDouble();
    }

    private static String safeMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return message == null || message.isBlank() ? "unknown failure" : message;
    }
}