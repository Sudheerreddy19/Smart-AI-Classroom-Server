package com.finalYear.smartClassRoom.ai.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

@Component
@Slf4j
public class OllamaModelProvider implements AIModelProvider {

    private final String baseUrl;
    private final String model;
    private final int timeoutSeconds;
    private final double temperature;

    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient client;

    public OllamaModelProvider(
            @Value("${app.ai.ollama.base-url:http://localhost:11434}") String baseUrl,
            @Value("${app.ai.ollama.model:llama3.2:3b}") String model,
            @Value("${app.ai.ollama.timeout-seconds:15}") int timeoutSeconds,
            @Value("${app.ai.ollama.temperature:0.7}") double temperature
    ) {
        this.baseUrl = baseUrl;
        this.model = model;
        this.timeoutSeconds = timeoutSeconds;
        this.temperature = temperature;
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(Math.min(5, timeoutSeconds)))
                .build();
    }

    @Override
    public String generateResponse(String systemPrompt, String userPrompt) {
        try {
            Map<String, Object> body = Map.of(
                    "model", model,
                    "stream", false,
                    "options", Map.of(
                            "temperature", temperature,
                            "num_predict", 300
                    ),
                    "messages", List.of(
                            Map.of("role", "system", "content", systemPrompt != null ? systemPrompt : ""),
                            Map.of("role", "user", "content", userPrompt)
                    )
            );

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/api/chat"))
                    .timeout(Duration.ofSeconds(timeoutSeconds))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                log.warn("Ollama returned status {}: {}", response.statusCode(), response.body());
                return "Unable to reach local Ollama model (HTTP " + response.statusCode() + ").";
            }

            JsonNode root = mapper.readTree(response.body());
            if (root.has("error")) {
                return "Ollama error: " + root.path("error").asText();
            }

            return root.path("message").path("content").asText("").trim();

        } catch (Exception ex) {
            log.warn("Ollama invocation failed: {}", ex.getMessage());
            return "Unable to reach local Ollama model. Please verify Ollama is running at " + baseUrl;
        }
    }

    @Override
    public String getProviderName() {
        return "Ollama";
    }

    @Override
    public String getModelName() {
        return model;
    }

    @Override
    public boolean isAvailable() {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/api/tags"))
                    .timeout(Duration.ofSeconds(2))
                    .GET()
                    .build();
            HttpResponse<Void> res = client.send(req, HttpResponse.BodyHandlers.discarding());
            return res.statusCode() == 200;
        } catch (Exception e) {
            return false;
        }
    }
}
