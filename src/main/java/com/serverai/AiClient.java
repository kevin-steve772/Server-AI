package com.serverai;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

public class AiClient {

    private final String apiKey;
    private final String endpoint;
    private final String model;
    private final int maxTokens;
    private final double temperature;
    private final int timeout;
    private final HttpClient httpClient;

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

    public String ask(String question) throws Exception {
        if (apiKey.isEmpty() || "your-api-key-here".equals(apiKey)) {
            throw new IllegalStateException("API key not configured");
        }

        String json = String.format(
                "{\"model\":\"%s\",\"messages\":[{\"role\":\"user\",\"content\":\"%s\"}],\"max_tokens\":%d,\"temperature\":%.1f}",
                escapeJson(model),
                escapeJson(question),
                maxTokens,
                temperature
        );

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

        return parseResponse(response.body());
    }

    private String parseResponse(String responseBody) {
        try {
            org.json.JSONObject json = new org.json.JSONObject(responseBody);
            return json.getJSONArray("choices")
                    .getJSONObject(0)
                    .getJSONObject("message")
                    .getString("content")
                    .trim();
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse API response: " + e.getMessage());
        }
    }

    private String escapeJson(String s) {
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}
