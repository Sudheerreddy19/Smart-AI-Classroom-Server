package com.finalYear.smartClassRoom.service.impl;

import com.finalYear.smartClassRoom.ai.model.AgentRequest;
import com.finalYear.smartClassRoom.ai.model.AgentResponse;
import com.finalYear.smartClassRoom.ai.orchestrator.AIOrchestrator;
import com.finalYear.smartClassRoom.dto.request.AIQueryRequest;
import com.finalYear.smartClassRoom.dto.response.AIQueryResponse;
import com.finalYear.smartClassRoom.dto.response.AISessionResponse;
import com.finalYear.smartClassRoom.entity.AIQuery;
import com.finalYear.smartClassRoom.entity.AISession;
import com.finalYear.smartClassRoom.entity.User;
import com.finalYear.smartClassRoom.exception.ResourceNotFoundException;
import com.finalYear.smartClassRoom.repository.AIQueryRepository;
import com.finalYear.smartClassRoom.repository.AISessionRepository;
import com.finalYear.smartClassRoom.repository.UserRepository;
import com.finalYear.smartClassRoom.service.AIService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

/**
 * AIServiceImpl — routes every query through the AIOrchestrator.
 * The Orchestrator dispatches to the right agent (Chat, Attendance, Marks,
 * Device, Quiz, Video …) based on intent and user role.
 */
@Service
@Slf4j
public class AIServiceImpl implements AIService {

    private final AISessionRepository aiSessionRepository;
    private final AIQueryRepository   aiQueryRepository;
    private final UserRepository      userRepository;
    private final AIOrchestrator      orchestrator;

    @Autowired
    public AIServiceImpl(AISessionRepository aiSessionRepository,
                         AIQueryRepository   aiQueryRepository,
                         UserRepository      userRepository,
                         AIOrchestrator      orchestrator) {
        this.aiSessionRepository = aiSessionRepository;
        this.aiQueryRepository   = aiQueryRepository;
        this.userRepository      = userRepository;
        this.orchestrator        = orchestrator;
    }

    // ── processQuery — the main entry point ───────────────────────────────────
    @Override
    @Transactional
    public AIQueryResponse processQuery(Long userId, AIQueryRequest request) {

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User", userId));

        // Resolve or create a session
        AISession session = (request.getSessionId() != null)
                ? aiSessionRepository.findById(request.getSessionId())
                        .orElse(newSession(user, request.getSessionTitle()))
                : newSession(user, request.getSessionTitle());

        // Retrieve previous conversation turns from session for multi-turn contextual memory
        java.util.List<java.util.Map<String, String>> conversationHistory = new java.util.ArrayList<>();
        try {
            java.util.List<AIQuery> pastQueries = aiQueryRepository.findBySession(session);
            if (pastQueries != null && !pastQueries.isEmpty()) {
                int start = Math.max(0, pastQueries.size() - 5);
                for (int i = start; i < pastQueries.size(); i++) {
                    AIQuery q = pastQueries.get(i);
                    if (q.getPrompt() != null && !q.getPrompt().isBlank()) {
                        conversationHistory.add(java.util.Map.of("role", "user", "text", q.getPrompt()));
                    }
                    if (q.getResponse() != null && !q.getResponse().isBlank()) {
                        conversationHistory.add(java.util.Map.of("role", "model", "text", q.getResponse()));
                    }
                }
            }
        } catch (Exception ex) {
            log.warn("Could not load past queries for conversation history: {}", ex.getMessage());
        }

        // Build the standardised agent request with conversation history
        AgentRequest agentReq = AgentRequest.builder()
                .userId(userId)
                .role(user.getRole())
                .prompt(request.getPrompt().trim())
                .sessionId(session.getId())
                .subjectContext(request.getSubjectContext())
                .conversationHistory(conversationHistory)
                .build();

        // Route through the AI Orchestrator
        AgentResponse agentResp = orchestrator.route(agentReq);
        log.info("====================================");
        log.info("Prompt : {}", request.getPrompt());
        log.info("Answer : {}", agentResp.getAnswer());
        log.info("Agent  : {}", agentResp.getAgentName());
        log.info("====================================");
        // Persist query + response
        AIQuery query = AIQuery.builder()
                .session(session)
                .prompt(request.getPrompt().trim())
                .response(agentResp.getAnswer())
                .queryType(request.getQueryType() != null
                        ? request.getQueryType()
                        : AIQuery.QueryType.GENERAL)
                .subjectContext(request.getSubjectContext())
                .tokensUsed(agentResp.getTokensUsed() > 0
                        ? agentResp.getTokensUsed()
                        : agentResp.getAnswer().length() / 4)
                .build();

        query = aiQueryRepository.save(query);
        session.setUpdatedAt(LocalDateTime.now());
        aiSessionRepository.save(session);

        log.info("AI query processed: userId={} agent={} ollamaGenerated={}",
                userId, agentResp.getAgentName(), agentResp.isOllamaGenerated());

        return toQueryResponse(query, session.getId());
    }

    // ── Session retrieval ─────────────────────────────────────────────────────

    @Override
    public AISessionResponse getSession(Long sessionId) {
        AISession session = aiSessionRepository.findById(sessionId)
                .orElseThrow(() -> new ResourceNotFoundException("AI Session", sessionId));
        List<AIQueryResponse> queries = aiQueryRepository.findBySession(session)
                .stream().map(q -> toQueryResponse(q, sessionId)).collect(Collectors.toList());
        return toSessionResponse(session, queries);
    }

    @Override
    public Page<AISessionResponse> getUserSessions(Long userId, Pageable pageable) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User", userId));
        return aiSessionRepository.findByUser(user, pageable)
                .map(s -> toSessionResponse(s, null));
    }

    @Override
    public Page<AISessionResponse> getSessions(Pageable pageable) {
        return aiSessionRepository.findAll(pageable)
                .map(s -> toSessionResponse(s, null));
    }

    @Override
    @Transactional
    public void deleteSession(Long sessionId) {
        if (!aiSessionRepository.existsById(sessionId))
            throw new ResourceNotFoundException("AI Session", sessionId);
        aiSessionRepository.deleteById(sessionId);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private AISession newSession(User user, String title) {
        return aiSessionRepository.save(AISession.builder()
                .title(title != null ? title : "AI Chat — " + LocalDateTime.now().toLocalDate())
                .user(user)
                .sessionType(AISession.SessionType.GENERAL)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build());
    }

    private AIQueryResponse toQueryResponse(AIQuery query, Long sessionId) {
        return AIQueryResponse.builder()
                .id(query.getId())
                .sessionId(sessionId != null ? sessionId : query.getSession().getId())
                .prompt(query.getPrompt())
                .response(query.getResponse())
                .queryType(query.getQueryType())
                .subjectContext(query.getSubjectContext())
                .tokensUsed(query.getTokensUsed())
                .createdAt(query.getCreatedAt())
                .build();
    }

    private AISessionResponse toSessionResponse(AISession s, List<AIQueryResponse> queries) {
        return AISessionResponse.builder()
                .id(s.getId())
                .title(s.getTitle())
                .sessionType(s.getSessionType())
                .createdAt(s.getCreatedAt())
                .updatedAt(s.getUpdatedAt())
                .queries(queries)
                .build();
    }
}
