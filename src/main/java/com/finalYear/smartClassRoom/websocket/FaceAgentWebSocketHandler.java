package com.finalYear.smartClassRoom.websocket;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.Map;

@Component
@Slf4j
@RequiredArgsConstructor
public class FaceAgentWebSocketHandler extends TextWebSocketHandler {

    private static final String ATTR_AUTHENTICATED = "AUTHENTICATED";
    private static final String ATTR_AGENT_ID      = "AGENT_ID";

    private final FaceAgentSessionManager sessionManager;
    private final ObjectMapper            objectMapper;

    @Value("${classai.agent.auth-secret:smart-classroom-agent-secret-key-2026}")
    private String expectedAuthSecret;

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        log.info("[AGENT-WS] Connection established from {} (session: {}). Awaiting AUTH handshake...",
                session.getRemoteAddress(), session.getId());
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        String payload = message.getPayload();
        Map<String, Object> data;
        try {
            data = objectMapper.readValue(payload, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            log.warn("[AGENT-WS] Failed to parse JSON from session {}: {}", session.getId(), e.getMessage());
            return;
        }

        String type = (String) data.get("type");
        if (type == null) {
            log.warn("[AGENT-WS] Message missing 'type' from session {}", session.getId());
            return;
        }

        switch (type) {
            case "AUTH" -> handleAuth(session, data);
            case "HEARTBEAT" -> handleHeartbeat(session, data);
            case "CAPTURE_RESULT" -> handleCaptureResult(session, data);
            case "PONG" -> log.debug("[AGENT-WS] PONG from session {}", session.getId());
            default -> log.warn("[AGENT-WS] Unknown message type '{}' from session {}", type, session.getId());
        }
    }

    private void handleAuth(WebSocketSession session, Map<String, Object> data) throws IOException {
        String agentId = (String) data.getOrDefault("agentId", "unknown-agent");
        String token   = (String) data.getOrDefault("token", "");

        // Authenticate: token must match expected secret
        boolean isValid = expectedAuthSecret != null && !expectedAuthSecret.isBlank()
                && expectedAuthSecret.equals(token);

        if (isValid) {
            session.getAttributes().put(ATTR_AUTHENTICATED, true);
            session.getAttributes().put(ATTR_AGENT_ID, agentId);
            sessionManager.registerSession(session, agentId);

            Map<String, Object> authOk = Map.of(
                    "type", "AUTH_OK",
                    "message", "Authenticated successfully",
                    "agentId", agentId
            );
            sendJson(session, authOk);
            log.info("[AGENT-WS] Agent '{}' AUTH_OK on session {}", agentId, session.getId());
        } else {
            log.warn("[AGENT-WS] Agent '{}' AUTH_FAIL: invalid token provided", agentId);
            Map<String, Object> authFail = Map.of(
                    "type", "AUTH_FAIL",
                    "reason", "Invalid authentication token"
            );
            sendJson(session, authFail);
            session.close(CloseStatus.POLICY_VIOLATION);
        }
    }

    private void handleHeartbeat(WebSocketSession session, Map<String, Object> data) throws IOException {
        if (!Boolean.TRUE.equals(session.getAttributes().get(ATTR_AUTHENTICATED))) {
            log.warn("[AGENT-WS] Unauthenticated heartbeat from session {}", session.getId());
            session.close(CloseStatus.POLICY_VIOLATION);
            return;
        }
        sessionManager.updateHeartbeat();
        sendJson(session, Map.of("type", "HEARTBEAT_ACK", "ts", System.currentTimeMillis()));
    }

    private void handleCaptureResult(WebSocketSession session, Map<String, Object> data) {
        if (!Boolean.TRUE.equals(session.getAttributes().get(ATTR_AUTHENTICATED))) {
            log.warn("[AGENT-WS] Unauthenticated CAPTURE_RESULT from session {}", session.getId());
            return;
        }
        String jobId = (String) data.get("jobId");
        if (jobId == null || jobId.isBlank()) {
            log.warn("[AGENT-WS] CAPTURE_RESULT missing jobId from session {}", session.getId());
            return;
        }
        sessionManager.completeJob(jobId, data);
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        log.error("[AGENT-WS] Transport error on session {}: {}", session.getId(), exception.getMessage());
        sessionManager.removeSession(session);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        log.info("[AGENT-WS] Connection closed for session {}: code={}, reason={}",
                session.getId(), status.getCode(), status.getReason());
        sessionManager.removeSession(session);
    }

    private void sendJson(WebSocketSession session, Map<String, Object> payload) throws IOException {
        if (session.isOpen()) {
            synchronized (session) {
                session.sendMessage(new TextMessage(objectMapper.writeValueAsString(payload)));
            }
        }
    }
}
