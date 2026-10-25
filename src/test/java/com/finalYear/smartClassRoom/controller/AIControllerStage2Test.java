package com.finalYear.smartClassRoom.controller;

import com.finalYear.smartClassRoom.ai.provider.AIModelProvider;
import com.finalYear.smartClassRoom.ai.provider.RateLimiterService;
import com.finalYear.smartClassRoom.config.JwtService;
import com.finalYear.smartClassRoom.service.AIService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AIController.class)
@AutoConfigureMockMvc(addFilters = false) // Bypass JWT filter for targeted unit test of controller endpoints
public class AIControllerStage2Test {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private AIService aiService;

    @MockBean
    private AIModelProvider aiModelProvider;

    @MockBean
    private RateLimiterService rateLimiterService;

    @MockBean
    private JwtService jwtService;

    @Test
    @DisplayName("GET /api/ai/provider-status should return active provider metadata")
    void testGetProviderStatus() throws Exception {
        when(aiModelProvider.getProviderName()).thenReturn("Google Gemini");
        when(aiModelProvider.getModelName()).thenReturn("gemini-3.8-flash");
        when(aiModelProvider.isAvailable()).thenReturn(true);
        when(rateLimiterService.getRemainingRequests(any())).thenReturn(14);

        mockMvc.perform(get("/api/ai/provider-status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.provider").value("Google Gemini"))
                .andExpect(jsonPath("$.model").value("gemini-3.8-flash"))
                .andExpect(jsonPath("$.available").value(true))
                .andExpect(jsonPath("$.remainingRequestsThisMinute").value(14));
    }

    @Test
    @DisplayName("POST /api/ai/test should return generated test response")
    void testMinimalEndpoint() throws Exception {
        when(rateLimiterService.tryAcquire(any())).thenReturn(true);
        when(aiModelProvider.getProviderName()).thenReturn("Google Gemini");
        when(aiModelProvider.getModelName()).thenReturn("gemini-3.8-flash");
        when(aiModelProvider.generateResponse(any(), any())).thenReturn("JARVIS Classroom AI is operational.");

        mockMvc.perform(post("/api/ai/test")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"prompt\":\"Ping\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.provider").value("Google Gemini"))
                .andExpect(jsonPath("$.model").value("gemini-3.8-flash"))
                .andExpect(jsonPath("$.response").value("JARVIS Classroom AI is operational."));
    }

    @Test
    @DisplayName("POST /api/ai/chat should return HTTP 429 when rate limit is exceeded")
    void testRateLimitEnforced() throws Exception {
        when(rateLimiterService.tryAcquire(any())).thenReturn(false);

        mockMvc.perform(post("/api/ai/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"What is the temperature?\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error").value("Too Many Requests"))
                .andExpect(jsonPath("$.remainingRequests").value(0));
    }
}
