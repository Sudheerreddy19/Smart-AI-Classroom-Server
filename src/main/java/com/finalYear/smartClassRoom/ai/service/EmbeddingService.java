package com.finalYear.smartClassRoom.ai.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

@Service
@Slf4j
public class EmbeddingService {

    private final String apiKey;
    private final String embeddingModel;
    private final int outputDimensionality;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient client;

    public EmbeddingService(
            @Value("${app.ai.gemini.api-key:}") String apiKey,
            @Value("${app.ai.rag.embedding-model:gemini-embedding-001}") String embeddingModel,
            @Value("${app.ai.rag.embedding-dim:768}") int outputDimensionality
    ) {
        this.apiKey = apiKey;
        this.embeddingModel = embeddingModel;
        this.outputDimensionality = outputDimensionality;
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    /**
     * Generate 768-dimensional embedding for text using Google Gemini Embedding API.
     */
    public float[] embedText(String text) {
        if (text == null || text.isBlank()) {
            return new float[outputDimensionality];
        }

        String url = "https://generativelanguage.googleapis.com/v1beta/models/"
                + embeddingModel + ":embedContent?key=" + apiKey;

        Map<String, Object> body = Map.of(
                "content", Map.of(
                        "parts", List.of(
                                Map.of("text", text)
                        )
                ),
                "outputDimensionality", outputDimensionality
        );

        int maxRetries = 2;
        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            try {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .timeout(Duration.ofSeconds(20))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                        .build();

                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

                if (response.statusCode() == 200) {
                    JsonNode root = mapper.readTree(response.body());
                    JsonNode valuesNode = root.path("embedding").path("values");

                    if (valuesNode.isArray() && valuesNode.size() > 0) {
                        float[] vector = new float[valuesNode.size()];
                        for (int i = 0; i < valuesNode.size(); i++) {
                            vector[i] = (float) valuesNode.get(i).asDouble();
                        }
                        return vector;
                    }
                }

                if (response.statusCode() == 429 && attempt < maxRetries) {
                    log.warn("Embedding API rate limit on attempt {}. Retrying with backoff...", attempt + 1);
                    Thread.sleep(1000L * (long) Math.pow(2, attempt));
                    continue;
                }

                log.error("Embedding API returned HTTP {}: {}", response.statusCode(), response.body());

            } catch (Exception e) {
                log.error("Error generating embedding on attempt {}: {}", attempt + 1, e.getMessage());
                if (attempt < maxRetries) {
                    try { Thread.sleep(1000L * (long) Math.pow(2, attempt)); } catch (InterruptedException ignored) {}
                }
            }
        }

        return new float[outputDimensionality];
    }

    /**
     * Serializes float[] to pgvector string format: "[0.012,-0.043,...]"
     */
    public String vectorToString(float[] vector) {
        if (vector == null || vector.length == 0) return "[]";
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < vector.length; i++) {
            sb.append(vector[i]);
            if (i < vector.length - 1) sb.append(",");
        }
        sb.append("]");
        return sb.toString();
    }

    /**
     * Parses pgvector string "[0.012,-0.043,...]" to float[]
     */
    public float[] stringToVector(String str) {
        if (str == null || str.length() < 2) return new float[0];
        String cleaned = str.trim();
        if (cleaned.startsWith("[") && cleaned.endsWith("]")) {
            cleaned = cleaned.substring(1, cleaned.length() - 1);
        }
        if (cleaned.isBlank()) return new float[0];

        String[] parts = cleaned.split(",");
        float[] vec = new float[parts.length];
        for (int i = 0; i < parts.length; i++) {
            try {
                vec[i] = Float.parseFloat(parts[i].trim());
            } catch (NumberFormatException e) {
                vec[i] = 0.0f;
            }
        }
        return vec;
    }

    /**
     * Calculates cosine similarity between two float vectors.
     * Returns a score between -1.0 and 1.0 (typically 0.0 to 1.0 for normalized embeddings).
     */
    public double cosineSimilarity(float[] vecA, float[] vecB) {
        if (vecA == null || vecB == null || vecA.length == 0 || vecB.length == 0) return 0.0;
        int len = Math.min(vecA.length, vecB.length);

        double dotProduct = 0.0;
        double normA = 0.0;
        double normB = 0.0;

        for (int i = 0; i < len; i++) {
            dotProduct += vecA[i] * vecB[i];
            normA += vecA[i] * vecA[i];
            normB += vecB[i] * vecB[i];
        }

        if (normA <= 0.0 || normB <= 0.0) return 0.0;
        return dotProduct / (Math.sqrt(normA) * Math.sqrt(normB));
    }
}
