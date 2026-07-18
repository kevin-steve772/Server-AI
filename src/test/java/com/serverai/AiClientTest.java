package com.serverai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiClientTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void sendsAnAsynchronousChatCompletionRequest() throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>();
        AtomicReference<String> authorization = new AtomicReference<>();
        startServer(exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(),
                    StandardCharsets.UTF_8));
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            respond(exchange, 200,
                    "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"  hello  \"}}]}");
        });

        AiClient client = client("secret", baseUrl() + "/v1", true);
        String answer = client.askAsync("question").get(5, TimeUnit.SECONDS);

        assertEquals("hello", answer);
        assertEquals("Bearer secret", authorization.get());
        JsonNode json = MAPPER.readTree(requestBody.get());
        assertEquals("test-model", json.path("model").asText());
        assertEquals("question", json.path("messages").path(0).path("content").asText());
        assertEquals(128, json.path("max_tokens").asInt());
    }

    @Test
    void acceptsAFullChatCompletionsEndpointWithoutAppendingItTwice() throws Exception {
        AtomicReference<String> path = new AtomicReference<>();
        AtomicReference<String> query = new AtomicReference<>();
        startServer(exchange -> {
            path.set(exchange.getRequestURI().getPath());
            query.set(exchange.getRequestURI().getRawQuery());
            respond(exchange, 200,
                    "{\"choices\":[{\"message\":{\"content\":\"ok\"}}]}");
        });

        AiClient client = client("secret",
                baseUrl() + "/v1/chat/completions?api-version=2025-01-01", true);
        assertEquals("ok", client.askAsync("question").get(5, TimeUnit.SECONDS));
        assertEquals("/v1/chat/completions", path.get());
        assertEquals("api-version=2025-01-01", query.get());
    }

    @Test
    void supportsLocalEndpointsWithoutAuthentication() throws Exception {
        AtomicReference<String> authorization = new AtomicReference<>();
        startServer(exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            respond(exchange, 200,
                    "{\"choices\":[{\"message\":{\"content\":\"local\"}}]}");
        });

        AiClient client = client("", baseUrl() + "/v1", false);
        assertTrue(client.isConfigured());
        assertEquals("local", client.askAsync("question").get(5, TimeUnit.SECONDS));
        assertNull(authorization.get());
    }

    @Test
    void reportsBoundedApiErrorDetails() throws Exception {
        startServer(exchange -> respond(exchange, 429,
                "{\"error\":{\"message\":\"rate limit reached\"}}"));

        ExecutionException exception = assertThrows(ExecutionException.class,
                () -> client("secret", baseUrl() + "/v1", true)
                        .askAsync("question").get(5, TimeUnit.SECONDS));

        AiClient.AiClientException cause = assertInstanceOf(
                AiClient.AiClientException.class, exception.getCause());
        assertTrue(cause.getMessage().contains("HTTP 429"));
        assertTrue(cause.getMessage().contains("rate limit reached"));
        assertFalse(cause.getMessage().contains("{\"error\""));
    }

    @Test
    void executesToolCallsWithoutBlockingTheHttpWorker() throws Exception {
        List<JsonNode> requests = Collections.synchronizedList(new ArrayList<>());
        AtomicReference<JsonNode> invocation = new AtomicReference<>();
        startServer(exchange -> {
            requests.add(MAPPER.readTree(exchange.getRequestBody()));
            if (requests.size() == 1) {
                respond(exchange, 200, """
                        {"choices":[{"message":{"role":"assistant","content":null,
                        "tool_calls":[{"id":"call-1","type":"function","function":
                        {"name":"lookup","arguments":"{\\"value\\":1}"}}]}}]}
                        """);
            } else {
                respond(exchange, 200,
                        "{\"choices\":[{\"message\":{\"content\":\"tool complete\"}}]}");
            }
        });

        AiClient client = client("secret", baseUrl() + "/v1", true);
        client.setTools(List.of(MAPPER.createObjectNode().put("type", "function")), call -> {
            invocation.set(call);
            return CompletableFuture.completedFuture("tool-result");
        });

        assertEquals("tool complete",
                client.askAsync("use a tool").get(5, TimeUnit.SECONDS));
        assertEquals(2, requests.size());
        assertEquals("lookup", invocation.get().path("name").asText());
        assertEquals(1, invocation.get().path("arguments").path("value").asInt());
        assertEquals("tool-result", requests.get(1).path("messages").path(2)
                .path("content").asText());
    }

    @Test
    void omitsToolsWhenTheRequesterIsNotAuthorized() throws Exception {
        AtomicReference<JsonNode> request = new AtomicReference<>();
        startServer(exchange -> {
            request.set(MAPPER.readTree(exchange.getRequestBody()));
            respond(exchange, 200,
                    "{\"choices\":[{\"message\":{\"content\":\"no tools\"}}]}");
        });

        AiClient client = client("secret", baseUrl() + "/v1", true);
        client.setTools(List.of(MAPPER.createObjectNode().put("type", "function")),
                call -> CompletableFuture.completedFuture("unused"));

        assertEquals("no tools", client.askWithFunctionsAsync(
                "question", null, false).get(5, TimeUnit.SECONDS));
        assertFalse(request.get().has("tools"));
        assertFalse(request.get().has("tool_choice"));
    }

    @Test
    void rejectsMissingRequiredApiKeyBeforeSending() {
        AiClient client = client("", "http://127.0.0.1:1/v1", true);
        assertFalse(client.isConfigured());

        ExecutionException exception = assertThrows(ExecutionException.class,
                () -> client.askAsync("question").get(5, TimeUnit.SECONDS));
        assertInstanceOf(AiClient.AiClientException.class, exception.getCause());
    }

    private AiClient client(String key, String endpoint, boolean requireKey) {
        return new AiClient(key, endpoint, "test-model", 128, 0.5, 5, 2, requireKey);
    }

    private void startServer(ExchangeHandler handler) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            try {
                handler.handle(exchange);
            } finally {
                exchange.close();
            }
        });
        server.start();
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    @FunctionalInterface
    private interface ExchangeHandler {
        void handle(HttpExchange exchange) throws IOException;
    }
}
