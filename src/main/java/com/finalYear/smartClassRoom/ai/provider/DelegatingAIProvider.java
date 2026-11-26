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
        return generateResponseWithHistory(systemPrompt, userPrompt, java.util.Collections.emptyList());
    }

    /**
     * Delegates multi-turn generation to Gemini or Ollama.
     */
    public String generateResponseWithHistory(String systemPrompt, String userPrompt, java.util.List<java.util.Map<String, String>> history) {
        if ("ollama".equalsIgnoreCase(configuredProvider)) {
            String ollamaResponse = ollamaProvider.generateResponse(systemPrompt, userPrompt);
            if (ollamaResponse != null && !ollamaResponse.isBlank()
                    && !ollamaResponse.startsWith("Unable to reach local Ollama")) {
                return ollamaResponse;
            }
            log.warn("Ollama unavailable, falling back to Gemini cloud model");
            return geminiProvider.generateResponseWithHistory(systemPrompt, userPrompt, history);
        } else {
            // Default: Gemini Cloud Provider with multi-turn support
            String geminiResponse = geminiProvider.generateResponseWithHistory(systemPrompt, userPrompt, history);
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
