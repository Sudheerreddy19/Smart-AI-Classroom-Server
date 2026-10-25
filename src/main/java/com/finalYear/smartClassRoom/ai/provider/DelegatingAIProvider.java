package com.finalYear.smartClassRoom.ai.provider;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

@Service
@Primary
@Slf4j
public class DelegatingAIProvider implements AIModelProvider {

    private final String configuredProvider;
    private final GeminiModelProvider geminiProvider;
    private final OllamaModelProvider ollamaProvider;

    public DelegatingAIProvider(
            @Value("${app.ai.provider:gemini}") String configuredProvider,
            GeminiModelProvider geminiProvider,
            OllamaModelProvider ollamaProvider
    ) {
        this.configuredProvider = configuredProvider;
        this.geminiProvider = geminiProvider;
        this.ollamaProvider = ollamaProvider;
        log.info("Initialized DelegatingAIProvider. Active default provider: {}", this.configuredProvider);
    }

    @Override
    public String generateResponse(String systemPrompt, String userPrompt) {
        if ("ollama".equalsIgnoreCase(configuredProvider)) {
            String ollamaResponse = ollamaProvider.generateResponse(systemPrompt, userPrompt);
            if (ollamaResponse != null && !ollamaResponse.isBlank()
                    && !ollamaResponse.startsWith("Unable to reach local Ollama")) {
                return ollamaResponse;
            }
            log.warn("Ollama unavailable, falling back to Gemini cloud model");
            return geminiProvider.generateResponse(systemPrompt, userPrompt);
        } else {
            // Default: Gemini Cloud Provider
            String geminiResponse = geminiProvider.generateResponse(systemPrompt, userPrompt);
            // If Gemini returned a connection failure and Ollama is running locally, attempt fallback
            if (geminiResponse.startsWith("Unable to reach the AI cloud service") && ollamaProvider.isAvailable()) {
                log.info("Gemini cloud unreachable, falling back to local Ollama");
                String fallback = ollamaProvider.generateResponse(systemPrompt, userPrompt);
                if (fallback != null && !fallback.isBlank()) {
                    return fallback;
                }
            }
            return geminiResponse;
        }
    }

    @Override
    public String getProviderName() {
        return "ollama".equalsIgnoreCase(configuredProvider) ? "Ollama" : "Google Gemini";
    }

    @Override
    public String getModelName() {
        return "ollama".equalsIgnoreCase(configuredProvider)
                ? ollamaProvider.getModelName()
                : geminiProvider.getModelName();
    }

    @Override
    public boolean isAvailable() {
        return "ollama".equalsIgnoreCase(configuredProvider)
                ? ollamaProvider.isAvailable()
                : geminiProvider.isAvailable();
    }
}
