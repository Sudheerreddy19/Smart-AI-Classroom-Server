package com.finalYear.smartClassRoom.ai.provider;

public interface AIModelProvider {

    /**
     * Generate response from the underlying AI model.
     *
     * @param systemPrompt Instructions or role persona (may be null/empty)
     * @param userPrompt The user's query or prompt
     * @return Model response text, or user-friendly error message
     */
    String generateResponse(String systemPrompt, String userPrompt);

    String getProviderName();

    String getModelName();

    boolean isAvailable();
}
