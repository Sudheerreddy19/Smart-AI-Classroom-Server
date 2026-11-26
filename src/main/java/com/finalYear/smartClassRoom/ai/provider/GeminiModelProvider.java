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
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.List;
import java.util.Map;

@Component
@Slf4j
public class GeminiModelProvider implements AIModelProvider {

    private final String apiKey;
    private final String model;
    private final int timeoutSeconds;
    private final double temperature;
    private final int maxRetries;
    private final long backoffMs;

    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient client;

    public GeminiModelProvider(
            @Value("${app.ai.gemini.api-key:}") String apiKey,
            @Value("${app.ai.gemini.model:gemini-3.8-flash}") String model,
            @Value("${app.ai.gemini.timeout-seconds:30}") int timeoutSeconds,
            @Value("${app.ai.gemini.temperature:0.7}") double temperature,
            @Value("${app.ai.gemini.max-retries:2}") int maxRetries,
            @Value("${app.ai.gemini.backoff-ms:1000}") long backoffMs
    ) {
        this.apiKey = apiKey;
        this.model = model;
        this.timeoutSeconds = timeoutSeconds;
        this.temperature = temperature;
        this.maxRetries = maxRetries;
        this.backoffMs = backoffMs;
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(Math.min(10, timeoutSeconds)))
                .build();

        log.info("Initialized GeminiModelProvider with model: {}, timeout: {}s, maxRetries: {}",
                this.model, this.timeoutSeconds, this.maxRetries);
    }

    @Override
    public String generateResponse(String systemPrompt, String userPrompt) {
        return generateResponseWithHistory(systemPrompt, userPrompt, java.util.Collections.emptyList());
    }

    /**
     * Multi-turn chat generation with conversation history mapped to Gemini user/model roles.
     */
    public String generateResponseWithHistory(String systemPrompt, String userPrompt, List<Map<String, String>> history) {
        if (apiKey == null || apiKey.isBlank()) {
            return "AI service authentication error. API key is not configured.";
        }

        String url = "https://generativelanguage.googleapis.com/v1beta/models/" + model + ":generateContent?key=" + apiKey;

        List<Map<String, Object>> contentsList = new java.util.ArrayList<>();

        // 1. Add previous conversation turns (limited to last 5 turns to preserve tokens)
        if (history != null && !history.isEmpty()) {
            int startIdx = Math.max(0, history.size() - 5);
            for (int i = startIdx; i < history.size(); i++) {
                Map<String, String> turn = history.get(i);
                String role = turn.getOrDefault("role", "user");
                String geminiRole = "assistant".equalsIgnoreCase(role) || "jarvis".equalsIgnoreCase(role) || "model".equalsIgnoreCase(role)
                        ? "model"
                        : "user";
                String text = turn.getOrDefault("content", turn.getOrDefault("text", ""));
                if (!text.isBlank()) {
                    contentsList.add(Map.of(
                            "role", geminiRole,
                            "parts", List.of(Map.of("text", text))
                    ));
                }
            }
        }

        // 2. Add current turn with system instructions injected if needed
        String currentTurnText = (systemPrompt != null && !systemPrompt.isBlank())
                ? (contentsList.isEmpty() ? (systemPrompt + "\n\nUser Question: " + userPrompt) : userPrompt)
                : userPrompt;

        contentsList.add(Map.of(
                "role", "user",
                "parts", List.of(Map.of("text", currentTurnText))
        ));

        Map<String, Object> bodyMap = new java.util.HashMap<>();
        bodyMap.put("contents", contentsList);
        bodyMap.put("generationConfig", Map.of("temperature", temperature));

        // Use systemInstruction object if systemPrompt is present and history exists
        if (systemPrompt != null && !systemPrompt.isBlank() && !contentsList.isEmpty()) {
            bodyMap.put("systemInstruction", Map.of(
                    "parts", List.of(Map.of("text", systemPrompt))
            ));
        }

        String jsonPayload;
        try {
            jsonPayload = mapper.writeValueAsString(bodyMap);
        } catch (Exception e) {
            log.error("Failed to serialize Gemini request body", e);
            return "Unable to process the request due to a serialization error.";
        }

        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            try {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .timeout(Duration.ofSeconds(timeoutSeconds))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(jsonPayload))
                        .build();

                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
                int statusCode = response.statusCode();

                if (statusCode == 200) {
                    JsonNode root = mapper.readTree(response.body());
                    JsonNode candidates = root.path("candidates");
                    if (candidates.isArray() && candidates.size() > 0) {
                        JsonNode parts = candidates.get(0).path("content").path("parts");
                        if (parts.isArray() && parts.size() > 0) {
                            String answer = parts.get(0).path("text").asText("").trim();
                            if (!answer.isBlank()) {
                                return answer;
                            }
                        }
                    }
                    return "I received an empty response. Please try rephrasing your question.";
                }

                // HTTP 429 - Rate limit / quota exhausted
                if (statusCode == 429) {
                    log.warn("Gemini rate limit exceeded (HTTP 429) on attempt {}/{}", attempt + 1, maxRetries + 1);
                    if (attempt < maxRetries) {
                        sleepWithBackoff(attempt);
                        continue;
                    }
                    return "AI service is currently at capacity or free-tier rate limits were exceeded. Please wait a minute and try again.";
                }

                // HTTP 400, 401, 403 - Invalid key or bad request (no retry needed)
                if (statusCode == 400 || statusCode == 401 || statusCode == 403) {
                    log.error("Gemini auth/request error (HTTP {}): {}", statusCode, response.body());
                    return "AI service authentication error. Please verify the API configuration.";
                }

                // HTTP 5xx - Cloud server errors (retryable)
                if (statusCode >= 500 && attempt < maxRetries) {
                    log.warn("Gemini server error (HTTP {}) on attempt {}/{}. Retrying...",
                            statusCode, attempt + 1, maxRetries + 1);
                    sleepWithBackoff(attempt);
                    continue;
                }

                log.error("Gemini returned unexpected status {}: {}", statusCode, response.body());
                return "AI service returned an unexpected status (" + statusCode + "). Please try again shortly.";

            } catch (HttpTimeoutException e) {
                log.warn("Gemini request timed out on attempt {}/{}", attempt + 1, maxRetries + 1);
                if (attempt < maxRetries) {
                    sleepWithBackoff(attempt);
                    continue;
                }
                return "AI service response timed out. Please try asking a shorter question or try again shortly.";

            } catch (Exception e) {
                log.error("Network or connection error calling Gemini on attempt {}/{}: {}",
                        attempt + 1, maxRetries + 1, e.getMessage());
                if (attempt < maxRetries) {
                    sleepWithBackoff(attempt);
                    continue;
                }
                return "Unable to reach the AI cloud service. Please check your internet connection.";
            }
        }

        return "Unable to complete AI request after retries. Please try again later.";
    }

    private void sleepWithBackoff(int attempt) {
        try {
            long delay = backoffMs * (long) Math.pow(2, attempt);
            Thread.sleep(delay);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public String getProviderName() {
        return "Google Gemini";
    }

    @Override
    public String getModelName() {
        return model;
    }

    @Override
    public boolean isAvailable() {
        return apiKey != null && !apiKey.isBlank();
    }
}
