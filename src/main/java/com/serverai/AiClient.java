package com.serverai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

public class AiClient {

    private final String apiKey;
    private final String endpoint;
    private final String model;
    private final int maxTokens;
    private final double temperature;
    private final int timeout;
    private final HttpClient httpClient;
    private final ObjectMapper mapper = new ObjectMapper();

    private List<ObjectNode> tools;
    private Function<JsonNode, CompletableFuture<String>> functionExecutor;

    public AiClient(String apiKey, String endpoint, String model, int maxTokens, double temperature, int timeout) {
        this.apiKey = apiKey;
        this.endpoint = endpoint.endsWith("/") ? endpoint + "chat/completions" : endpoint + "/chat/completions";
        this.model = model;
        this.maxTokens = maxTokens;
        this.temperature = temperature;
        this.timeout = timeout;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(timeout))
                .build();
    }

    public void setTools(List<ObjectNode> tools, Function<JsonNode, CompletableFuture<String>> executor) {
        this.tools = tools;
        this.functionExecutor = executor;
    }

    public List<ObjectNode> getToolDefinitions() {
        return tools;
    }

    public String ask(String question) throws Exception {
        return askWithFunctions(question, null);
    }

    public String askWithFunctions(String question, List<Map<String, Object>> history) throws Exception {
        if (apiKey.isEmpty() || "your-api-key-here".equals(apiKey)) {
            throw new IllegalStateException("API key not configured");
        }

        ArrayNode messages = mapper.createArrayNode();
        if (history != null) {
            for (Map<String, Object> msg : history) {
                ObjectNode m = mapper.createObjectNode();
                msg.forEach(m::putPOJO);
                messages.add(m);
            }
        }
        messages.add(createMessage("user", question));

        ObjectNode requestBody = mapper.createObjectNode();
        requestBody.put("model", model);
        requestBody.set("messages", messages);
        requestBody.put("max_tokens", maxTokens);
        requestBody.put("temperature", temperature);

        if (tools != null && !tools.isEmpty()) {
            ArrayNode toolsArray = mapper.createArrayNode();
            tools.forEach(toolsArray::add);
            requestBody.set("tools", toolsArray);
            requestBody.put("tool_choice", "auto");
        }

        String json = mapper.writeValueAsString(requestBody);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(endpoint))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .timeout(Duration.ofSeconds(timeout))
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new RuntimeException("API returned status " + response.statusCode() + ": " + response.body());
        }

        JsonNode respJson = mapper.readTree(response.body());
        JsonNode choice = respJson.get("choices").get(0);
        JsonNode message = choice.get("message");

        if (message.has("tool_calls")) {
            return handleToolCalls(message, messages);
        }

        return message.get("content").asText().trim();
    }

    private String handleToolCalls(JsonNode message, ArrayNode messages) throws Exception {
        JsonNode toolCalls = message.get("tool_calls");
        messages.add(message);

        for (JsonNode call : toolCalls) {
            String callId = call.get("id").asText();
            String fnName = call.get("function").get("name").asText();
            JsonNode fnArgs = mapper.readTree(call.get("function").get("arguments").asText());

            String result;
            if (functionExecutor != null) {
                result = functionExecutor.apply(mapper.createObjectNode()
                        .put("name", fnName)
                        .set("arguments", fnArgs)).join();
            } else {
                result = "Function executor not available";
            }

            ObjectNode toolResult = mapper.createObjectNode();
            toolResult.put("role", "tool");
            toolResult.put("tool_call_id", callId);
            toolResult.put("content", result);
            messages.add(toolResult);
        }

        ObjectNode requestBody = mapper.createObjectNode();
        requestBody.put("model", model);
        requestBody.set("messages", messages);
        requestBody.put("max_tokens", maxTokens);
        requestBody.put("temperature", temperature);

        String json = mapper.writeValueAsString(requestBody);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(endpoint))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .timeout(Duration.ofSeconds(timeout))
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new RuntimeException("API returned status " + response.statusCode() + ": " + response.body());
        }

        JsonNode respJson = mapper.readTree(response.body());
        return respJson.get("choices").get(0).get("message").get("content").asText().trim();
    }

    private ObjectNode createMessage(String role, String content) {
        ObjectNode msg = mapper.createObjectNode();
        msg.put("role", role);
        msg.put("content", content);
        return msg;
    }

    private String escapeJson(String s) {
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}