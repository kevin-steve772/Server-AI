package com.serverai.npc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.serverai.Main;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;

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

    private void completeMove(CompletableFuture<String> result,
                              CompletableFuture<Boolean> movement) {
        movement.whenComplete((started, error) -> {
            if (error != null) {
                result.completeExceptionally(error);
            } else if (Boolean.TRUE.equals(started)) {
                result.complete("NPC started moving");
            } else if (!npcManager.isSpawned()) {
                result.complete("Error: NPC is not spawned");
            } else {
                result.complete("Error: destination is in another world, too far away, or unreachable");
            }
        });
    }

    private List<ObjectNode> createDefinitions() {
        return List.of(
                tool("npc_move",
                        "Move the spawned Minecraft NPC to coordinates in its current world.",
                        properties(
                                property("world", "string", "Exact Minecraft world name"),
                                property("x", "number", "Target X coordinate"),
                                property("y", "number", "Target Y coordinate"),
                                property("z", "number", "Target Z coordinate"),
                                property("speed", "number", "Optional speed from 0.1 to 2.0")),
                        "world", "x", "y", "z"),
                tool("npc_move_to_player",
                        "Move the spawned NPC to an online player. Prefer this when asked to come or follow once.",
                        properties(
                                property("player", "string", "Exact online player name"),
                                property("speed", "number", "Optional speed from 0.1 to 2.0")),
                        "player"),
                tool("npc_stop", "Stop the spawned NPC immediately.",
                        mapper.createObjectNode()),
                tool("npc_say", "Make the spawned NPC broadcast a short message.",
                        properties(property("message", "string", "Message for the NPC to say")),
                        "message"),
                tool("npc_get_location", "Get the spawned NPC location and movement state.",
                        mapper.createObjectNode()));
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
