package com.serverai;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

public final class AiClient {

    private static final int MAX_ERROR_LENGTH = 300;

    private final String apiKey;
    private final boolean requireKey;
    private final URI endpoint;
    private final String model;
    private final int maxTokens;
    private final double temperature;
    private final int timeout;
    private final int maxConcurrentRequests;
    private final AtomicInteger inFlightRequests = new AtomicInteger();
    private final HttpClient httpClient;
    private final ObjectMapper mapper = new ObjectMapper();

    private volatile List<ObjectNode> tools = List.of();
    private volatile Function<JsonNode, CompletableFuture<String>> functionExecutor;

    public AiClient(String apiKey, String endpoint, String model, int maxTokens,
                    double temperature, int timeout) {
        this(apiKey, endpoint, model, maxTokens, temperature, timeout, 4, true);
    }

    public AiClient(String apiKey, String endpoint, String model, int maxTokens,
                    double temperature, int timeout, int maxConcurrentRequests,
                    boolean requireKey) {
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.requireKey = requireKey;
        this.endpoint = normalizeEndpoint(endpoint);
        this.model = requireText(model, "API model");
        this.maxTokens = Math.max(1, maxTokens);
        this.temperature = Math.max(0.0, Math.min(2.0, temperature));
        this.timeout = Math.max(1, timeout);
        this.maxConcurrentRequests = Math.max(1, maxConcurrentRequests);
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(this.timeout))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .version(HttpClient.Version.HTTP_2)
                .build();
    }

    public void setTools(List<ObjectNode> tools,
                         Function<JsonNode, CompletableFuture<String>> executor) {
        this.tools = tools == null ? List.of() : List.copyOf(tools);
        this.functionExecutor = executor;
    }

    public List<ObjectNode> getToolDefinitions() {
        return tools;
    }

    public boolean isConfigured() {
        return !requireKey || hasApiKey();
    }

    public CompletableFuture<String> askAsync(String question) {
        return askWithFunctionsAsync(question, null);
    }

    public CompletableFuture<String> askWithFunctionsAsync(
            String question, List<Map<String, Object>> history) {
        return askWithFunctionsAsync(question, history, true);
    }

    public CompletableFuture<String> askWithFunctionsAsync(
            String question, List<Map<String, Object>> history, boolean allowToolCalls) {
        if (!isConfigured()) {
            return CompletableFuture.failedFuture(
                    new AiClientException("API key is not configured"));
        }

        String validatedQuestion;
        try {
            validatedQuestion = requireText(question, "Question");
        } catch (IllegalArgumentException exception) {
            return CompletableFuture.failedFuture(exception);
        }

        ArrayNode messages = mapper.createArrayNode();
        if (history != null) {
            for (Map<String, Object> item : history) {
                ObjectNode message = mapper.createObjectNode();
                item.forEach(message::putPOJO);
                messages.add(message);
            }
        }
        messages.add(createMessage("user", validatedQuestion));

        return sendMessages(messages, allowToolCalls).thenCompose(message -> {
            if (allowToolCalls && message.has("tool_calls")
                    && message.get("tool_calls").isArray()) {
                return handleToolCalls(message, messages);
            }
            return completedContent(message);
        });
    }

    public String ask(String question) throws Exception {
        return await(askAsync(question));
    }

    public String askWithFunctions(String question, List<Map<String, Object>> history)
            throws Exception {
        return await(askWithFunctionsAsync(question, history));
    }

    private CompletableFuture<String> handleToolCalls(JsonNode assistantMessage,
                                                       ArrayNode messages) {
        JsonNode toolCalls = assistantMessage.get("tool_calls");
        if (toolCalls.isEmpty()) {
            return completedContent(assistantMessage);
        }
        messages.add(assistantMessage);

        List<CompletableFuture<ObjectNode>> results = new ArrayList<>();
        for (JsonNode call : toolCalls) {
            results.add(executeToolCall(call));
        }

        CompletableFuture<Void> allResults = CompletableFuture.allOf(
                results.toArray(CompletableFuture[]::new));
        return allResults.thenCompose(ignored -> {
            results.forEach(result -> messages.add(result.join()));
            return sendMessages(messages, false);
        }).thenCompose(this::completedContent);
    }

    private CompletableFuture<ObjectNode> executeToolCall(JsonNode call) {
        String callId = requiredField(call, "id");
        JsonNode function = call.path("function");
        String functionName = requiredField(function, "name");

        JsonNode arguments;
        try {
            arguments = mapper.readTree(requiredField(function, "arguments"));
        } catch (JsonProcessingException exception) {
            return CompletableFuture.failedFuture(
                    new AiClientException("API returned invalid tool arguments", exception));
        }

        Function<JsonNode, CompletableFuture<String>> executor = functionExecutor;
        CompletableFuture<String> result;
        if (executor == null) {
            result = CompletableFuture.completedFuture("Function executor not available");
        } else {
            ObjectNode invocation = mapper.createObjectNode();
            invocation.put("name", functionName);
            invocation.set("arguments", arguments);
            try {
                result = executor.apply(invocation);
                if (result == null) {
                    result = CompletableFuture.failedFuture(
                            new AiClientException("Function executor returned no result"));
                }
            } catch (RuntimeException exception) {
                result = CompletableFuture.failedFuture(exception);
            }
        }

        return result.thenApply(content -> {
            ObjectNode toolResult = mapper.createObjectNode();
            toolResult.put("role", "tool");
            toolResult.put("tool_call_id", callId);
            toolResult.put("content", content == null ? "" : content);
            return toolResult;
        });
    }

    private CompletableFuture<JsonNode> sendMessages(ArrayNode messages, boolean includeTools) {
        ObjectNode requestBody = mapper.createObjectNode();
        requestBody.put("model", model);
        requestBody.set("messages", messages);
        requestBody.put("max_tokens", maxTokens);
        requestBody.put("temperature", temperature);

        List<ObjectNode> currentTools = tools;
        if (includeTools && !currentTools.isEmpty()) {
            ArrayNode toolArray = mapper.createArrayNode();
            currentTools.forEach(toolArray::add);
            requestBody.set("tools", toolArray);
            requestBody.put("tool_choice", "auto");
        }

        HttpRequest.Builder request = HttpRequest.newBuilder()
                .uri(endpoint)
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(timeout))
                .POST(HttpRequest.BodyPublishers.ofString(
                        requestBody.toString(), StandardCharsets.UTF_8));
        if (hasApiKey()) {
            request.header("Authorization", "Bearer " + apiKey);
        }

        return send(request.build()).thenApply(this::parseMessage);
    }

    private CompletableFuture<HttpResponse<String>> send(HttpRequest request) {
        int inFlight = inFlightRequests.incrementAndGet();
        if (inFlight > maxConcurrentRequests) {
            inFlightRequests.decrementAndGet();
            return CompletableFuture.failedFuture(
                    new AiClientException("Too many concurrent AI requests"));
        }

        try {
            return httpClient.sendAsync(request,
                            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                    .whenComplete((ignored, error) -> inFlightRequests.decrementAndGet());
        } catch (RuntimeException exception) {
            inFlightRequests.decrementAndGet();
            return CompletableFuture.failedFuture(exception);
        }
    }

    private JsonNode parseMessage(HttpResponse<String> response) {
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new AiClientException("API returned HTTP " + response.statusCode()
                    + ": " + extractApiError(response.body()));
        }

        try {
            JsonNode root = mapper.readTree(response.body());
            if (root == null) {
                throw new AiClientException("API returned an empty response");
            }
            JsonNode message = root.path("choices").path(0).path("message");
            if (message.isMissingNode() || !message.isObject()) {
                throw new AiClientException("API response does not contain a message");
            }
            return message;
        } catch (JsonProcessingException exception) {
            throw new AiClientException("API returned invalid JSON", exception);
        }
    }

    private CompletableFuture<String> completedContent(JsonNode message) {
        JsonNode content = message.get("content");
        if (content == null || !content.isTextual() || content.asText().isBlank()) {
            return CompletableFuture.failedFuture(
                    new AiClientException("API response does not contain text content"));
        }
        return CompletableFuture.completedFuture(content.asText().trim());
    }

    private String extractApiError(String body) {
        String detail = body;
        try {
            JsonNode root = mapper.readTree(body);
            if (root != null) {
                JsonNode errorMessage = root.path("error").path("message");
                if (errorMessage.isTextual()) {
                    detail = errorMessage.asText();
                }
            }
        } catch (JsonProcessingException ignored) {
            // Fall back to a bounded, normalized version of the raw response.
        }
        String normalized = detail == null ? "No response body"
                : detail.replaceAll("\\s+", " ").trim();
        if (normalized.isEmpty()) {
            normalized = "No response body";
        }
        return normalized.length() <= MAX_ERROR_LENGTH
                ? normalized
                : normalized.substring(0, MAX_ERROR_LENGTH) + "...";
    }

    private ObjectNode createMessage(String role, String content) {
        ObjectNode message = mapper.createObjectNode();
        message.put("role", role);
        message.put("content", content);
        return message;
    }

    private boolean hasApiKey() {
        return !apiKey.isBlank() && !"your-api-key-here".equals(apiKey);
    }

    private static URI normalizeEndpoint(String endpoint) {
        String value = requireText(endpoint, "API endpoint");
        URI source;
        try {
            source = URI.create(value);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("API endpoint is not a valid URI", exception);
        }
        String scheme = source.getScheme();
        if (scheme == null || !(scheme.equalsIgnoreCase("http")
                || scheme.equalsIgnoreCase("https")) || source.getHost() == null) {
            throw new IllegalArgumentException("API endpoint must be an HTTP(S) URI");
        }

        String path = source.getPath();
        while (path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        if (!path.endsWith("/chat/completions")) {
            path += "/chat/completions";
        }
        try {
            return new URI(source.getScheme(), source.getUserInfo(), source.getHost(),
                    source.getPort(), path, source.getQuery(), null);
        } catch (Exception exception) {
            throw new IllegalArgumentException("API endpoint is not a valid URI", exception);
        }
    }

    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value.trim();
    }

    private static String requiredField(JsonNode node, String fieldName) {
        JsonNode value = node.get(fieldName);
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            throw new AiClientException("API response is missing field: " + fieldName);
        }
        return value.asText();
    }

    private static String await(CompletableFuture<String> future) throws Exception {
        try {
            return future.get();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw exception;
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof Exception checkedException) {
                throw checkedException;
            }
            throw new RuntimeException(cause);
        }
    }

    public static final class AiClientException extends RuntimeException {
        public AiClientException(String message) {
            super(message);
        }

        public AiClientException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
