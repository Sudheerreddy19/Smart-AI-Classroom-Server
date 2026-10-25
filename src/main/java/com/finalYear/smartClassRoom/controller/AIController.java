package com.finalYear.smartClassRoom.controller;

import com.finalYear.smartClassRoom.ai.provider.AIModelProvider;
import com.finalYear.smartClassRoom.ai.provider.RateLimiterService;
import com.finalYear.smartClassRoom.dto.request.AIQueryRequest;
import com.finalYear.smartClassRoom.dto.response.AIQueryResponse;
import com.finalYear.smartClassRoom.dto.response.AISessionResponse;
import com.finalYear.smartClassRoom.service.AIService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/ai")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
@Slf4j
public class AIController {

    private final AIService aiService;
    private final AIModelProvider aiModelProvider;
    private final RateLimiterService rateLimiterService;

    // ── Simple chat endpoint (POST /api/ai/chat) ──────────────────────────────
    @PostMapping("/chat")
    public ResponseEntity<Map<String, Object>> chat(@RequestBody Map<String, String> body) {
        String question = body.getOrDefault("question", body.getOrDefault("prompt", ""));
        if (question.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Question is required"));
        }

        Long userId = resolveCurrentUserId();
        String userKey = "user_" + userId;

        // Per-user rate limiting to protect free-tier quotas
        if (!rateLimiterService.tryAcquire(userKey)) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(Map.of(
                    "error", "Too Many Requests",
                    "message", "Free-tier rate limit exceeded (15 requests/minute). Please wait a moment and try again.",
                    "answer", "AI service is currently rate-limited. Please wait a minute before asking again.",
                    "remainingRequests", 0
            ));
        }

        AIQueryRequest request = new AIQueryRequest();
        request.setPrompt(question);
        request.setQueryType(com.finalYear.smartClassRoom.entity.AIQuery.QueryType.GENERAL);

        AIQueryResponse response = aiService.processQuery(userId, request);
        return ResponseEntity.ok(Map.of(
                "answer", response.getResponse(),
                "sessionId", response.getSessionId() != null ? response.getSessionId() : 1L,
                "queryId", response.getId() != null ? response.getId() : System.currentTimeMillis(),
                "provider", aiModelProvider.getProviderName(),
                "remainingRequests", rateLimiterService.getRemainingRequests(userKey)
        ));
    }

    // ── Minimal Test & Verification Endpoint (POST /api/ai/test) ──────────────
    @PostMapping("/test")
    public ResponseEntity<Map<String, Object>> testModel(@RequestBody(required = false) Map<String, String> body) {
        String prompt = (body != null && body.containsKey("prompt"))
                ? body.get("prompt")
                : "Hello! In 5 words, confirm you are operational.";

        Long userId = resolveCurrentUserId();
        String userKey = "test_" + userId;

        if (!rateLimiterService.tryAcquire(userKey)) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(Map.of(
                    "error", "Rate limit exceeded for test endpoint. Please wait a minute."
            ));
        }

        long start = System.currentTimeMillis();
        String answer = aiModelProvider.generateResponse("You are a test validator.", prompt);
        long elapsed = System.currentTimeMillis() - start;

        return ResponseEntity.ok(Map.of(
                "status", "SUCCESS",
                "provider", aiModelProvider.getProviderName(),
                "model", aiModelProvider.getModelName(),
                "prompt", prompt,
                "response", answer,
                "responseTimeMs", elapsed
        ));
    }

    // ── Provider Health / Status (GET /api/ai/provider-status) ────────────────
    @GetMapping("/provider-status")
    public ResponseEntity<Map<String, Object>> getProviderStatus() {
        Long userId = resolveCurrentUserId();
        String userKey = "user_" + userId;

        return ResponseEntity.ok(Map.of(
                "provider", aiModelProvider.getProviderName(),
                "model", aiModelProvider.getModelName(),
                "available", aiModelProvider.isAvailable(),
                "remainingRequestsThisMinute", rateLimiterService.getRemainingRequests(userKey)
        ));
    }

    @PostMapping("/query/{userId}")
    public ResponseEntity<AIQueryResponse> processQuery(
            @PathVariable Long userId,
            @Valid @RequestBody AIQueryRequest request) {

        String userKey = "user_" + userId;
        if (!rateLimiterService.tryAcquire(userKey)) {
            throw new RuntimeException("Rate limit reached. Please wait a minute before making another query.");
        }

        AIQueryResponse response = aiService.processQuery(userId, request);
        log.info("Question : {}", request.getPrompt());
        log.info("AI Answer: {}", response.getResponse());

        return ResponseEntity.ok(response);
    }

    // ── Session management ────────────────────────────────────────────────────
    @GetMapping("/session/{sessionId}")
    public ResponseEntity<AISessionResponse> getSession(@PathVariable Long sessionId) {
        return ResponseEntity.ok(aiService.getSession(sessionId));
    }

    @GetMapping("/user/{userId}/sessions")
    public ResponseEntity<Page<AISessionResponse>> getUserSessions(
            @PathVariable Long userId, Pageable pageable) {
        return ResponseEntity.ok(aiService.getUserSessions(userId, pageable));
    }

    @GetMapping("/sessions")
    public ResponseEntity<Page<AISessionResponse>> getSessions(Pageable pageable) {
        return ResponseEntity.ok(aiService.getSessions(pageable));
    }

    @DeleteMapping("/session/{sessionId}")
    public ResponseEntity<String> deleteSession(@PathVariable Long sessionId) {
        aiService.deleteSession(sessionId);
        return ResponseEntity.ok("AI Session deleted successfully.");
    }

    // ── Helper ────────────────────────────────────────────────────────────────
    private Long resolveCurrentUserId() {
        try {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth != null && auth.getPrincipal() instanceof com.finalYear.smartClassRoom.entity.User user) {
                return user.getId();
            }
        } catch (Exception ignored) {}
        return 27L; // default fallback ID
    }
}
