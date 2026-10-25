package com.finalYear.smartClassRoom.ai.provider;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class AIProviderStage2Test {

    @Test
    @DisplayName("GeminiModelProvider should successfully generate response with valid key")
    void testGeminiModelProviderLiveCall() {
        String apiKey = System.getenv("GEMINI_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            return; // Skip live external API test if no env key provided
        }
        GeminiModelProvider provider = new GeminiModelProvider(
                apiKey,
                "gemini-3.8-flash",
                30,
                0.7,
                2,
                1000
        );

        assertTrue(provider.isAvailable());
        assertEquals("Google Gemini", provider.getProviderName());
        assertEquals("gemini-3.8-flash", provider.getModelName());

        String response = provider.generateResponse(
                "You are a classroom AI test validator.",
                "In exactly 3 words, confirm readiness."
        );

        System.out.println("=================================================");
        System.out.println("[TEST LIVE OUTPUT] Gemini Response: " + response);
        System.out.println("=================================================");

        assertNotNull(response);
        assertFalse(response.isBlank());
        assertFalse(response.startsWith("AI service authentication error"));
        assertTrue(response.length() > 0);
    }

    @Test
    @DisplayName("GeminiModelProvider should return user-friendly message on blank/missing key")
    void testGeminiModelProviderMissingKey() {
        GeminiModelProvider provider = new GeminiModelProvider(
                "",
                "gemini-3.8-flash",
                5,
                0.7,
                0,
                500
        );

        assertFalse(provider.isAvailable());
        String response = provider.generateResponse("System", "Hello");
        assertEquals("AI service authentication error. API key is not configured.", response);
    }

    @Test
    @DisplayName("RateLimiterService should enforce per-user rate limit")
    void testRateLimiterService() {
        RateLimiterService rateLimiter = new RateLimiterService(3);
        String testUser = "test_user_stage2";

        // First 3 requests should be permitted
        assertTrue(rateLimiter.tryAcquire(testUser));
        assertTrue(rateLimiter.tryAcquire(testUser));
        assertTrue(rateLimiter.tryAcquire(testUser));

        // 4th request must be rejected under limit of 3
        assertFalse(rateLimiter.tryAcquire(testUser));
        assertEquals(0, rateLimiter.getRemainingRequests(testUser));

        // Different user should have their own fresh bucket
        String anotherUser = "another_user";
        assertTrue(rateLimiter.tryAcquire(anotherUser));
        assertEquals(2, rateLimiter.getRemainingRequests(anotherUser));
    }

    @Test
    @DisplayName("DelegatingAIProvider delegates to Gemini by default")
    void testDelegatingAIProvider() {
        GeminiModelProvider gemini = new GeminiModelProvider(
                "test-dummy-api-key",
                "gemini-3.8-flash",
                30,
                0.7,
                1,
                500
        );
        OllamaModelProvider ollama = new OllamaModelProvider(
                "http://localhost:11434",
                "llama3.2:3b",
                5,
                0.7
        );

        DelegatingAIProvider delegating = new DelegatingAIProvider("gemini", gemini, ollama);

        assertEquals("Google Gemini", delegating.getProviderName());
        assertEquals("gemini-3.8-flash", delegating.getModelName());
    }
}
