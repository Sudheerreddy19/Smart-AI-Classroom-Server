package com.finalYear.smartClassRoom.websocket;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finalYear.smartClassRoom.dto.request.NotificationRequest;
import com.finalYear.smartClassRoom.dto.response.FaceAttendanceResult;
import com.finalYear.smartClassRoom.entity.Notification;
import com.finalYear.smartClassRoom.entity.User;
import com.finalYear.smartClassRoom.service.AttendanceService;
import com.finalYear.smartClassRoom.service.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
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
public class FaceAgentWebSocketHandler extends TextWebSocketHandler {

    private static final String ATTR_AUTHENTICATED = "AUTHENTICATED";
    private static final String ATTR_AGENT_ID      = "AGENT_ID";

    private final FaceAgentSessionManager sessionManager;
    private final ObjectMapper            objectMapper;
    private final AttendanceService       attendanceService;
    private final NotificationService     notificationService;

    public FaceAgentWebSocketHandler(
            FaceAgentSessionManager sessionManager,
            ObjectMapper objectMapper,
            @Lazy AttendanceService attendanceService,
            @Lazy NotificationService notificationService) {
        this.sessionManager = sessionManager;
        this.objectMapper = objectMapper;
        this.attendanceService = attendanceService;
        this.notificationService = notificationService;
    }

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
            case "RECOGNITION_EVENT" -> handleRecognitionEvent(session, data);
            case "UNKNOWN_FACE_EVENT" -> handleUnknownFaceEvent(session, data);
            case "CAMERA_STATUS" -> handleCameraStatus(session, data);
            case "ATTENDANCE_COMPLETE" -> handleAttendanceComplete(session, data);
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

    private void handleRecognitionEvent(WebSocketSession session, Map<String, Object> data) {
        if (!Boolean.TRUE.equals(session.getAttributes().get(ATTR_AUTHENTICATED))) {
            log.warn("[AGENT-WS] Unauthenticated RECOGNITION_EVENT from session {}", session.getId());
            return;
        }
        Object sessionIdObj = data.get("sessionId");
        String rollNumber   = (String) data.get("rollNumber");
        Object studentIdObj = data.get("studentId");
        Long studentDbId    = null;
        if (studentIdObj instanceof Number n) {
            studentDbId = n.longValue();
        } else if (studentIdObj != null) {
            try {
                studentDbId = Long.parseLong(studentIdObj.toString());
            } catch (NumberFormatException ignored) {}
        }

        if (sessionIdObj == null || (rollNumber == null && studentDbId == null)) {
            log.warn("[AGENT-WS] RECOGNITION_EVENT missing identifiers: {}", data);
            return;
        }
        Long sessionId;
        try {
            sessionId = sessionIdObj instanceof Number n ? n.longValue() : Long.parseLong(sessionIdObj.toString());
        } catch (NumberFormatException e) {
            log.warn("[AGENT-WS] RECOGNITION_EVENT invalid sessionId: {}", sessionIdObj);
            return;
        }
        Double confidence = data.get("confidence") instanceof Number n ? n.doubleValue() : null;

        try {
            FaceAttendanceResult result = attendanceService.markRecognizedStudent(sessionId, studentDbId, rollNumber, confidence);
            if (result.isSuccess()) {
                log.info("[AGENT-WS] RECOGNITION_EVENT marked PRESENT: id={} roll={} session={}", studentDbId, rollNumber, sessionId);
            } else {
                log.debug("[AGENT-WS] RECOGNITION_EVENT result: code={} id={} roll={} session={}",
                        result.getCode(), studentDbId, rollNumber, sessionId);
            }
        } catch (Exception e) {
            log.error("[AGENT-WS] Error handling RECOGNITION_EVENT: {}", e.getMessage(), e);
        }
    }

    private void handleUnknownFaceEvent(WebSocketSession session, Map<String, Object> data) {
        if (!Boolean.TRUE.equals(session.getAttributes().get(ATTR_AUTHENTICATED))) {
            log.warn("[AGENT-WS] Unauthenticated UNKNOWN_FACE_EVENT from session {}", session.getId());
            return;
        }
        Object sessionIdObj = data.get("sessionId");
        String timestamp    = data.getOrDefault("timestamp", "").toString();
        Long sessionId = null;
        if (sessionIdObj != null) {
            try {
                sessionId = sessionIdObj instanceof Number n ? n.longValue() : Long.parseLong(sessionIdObj.toString());
            } catch (NumberFormatException e) {
                log.warn("[AGENT-WS] UNKNOWN_FACE_EVENT invalid sessionId: {}", sessionIdObj);
            }
        }
        log.warn("[AGENT-WS] UNKNOWN_FACE_EVENT received: session={} timestamp={}", sessionId, timestamp);

        try {
            NotificationRequest notif = new NotificationRequest();
            notif.setTitle("Unrecognized Face Detected");
            notif.setMessage(String.format(
                    "An unrecognized face was detected during attendance session %s at %s. Please review footage.",
                    sessionId != null ? "#" + sessionId : "(unknown)",
                    timestamp.isBlank() ? "current session" : timestamp));
            notif.setType(Notification.NotificationType.ALERT);
            notif.setUserId(null);
            notif.setTargetRole(User.Role.ADMIN);
            notificationService.create(notif);
            log.info("[AGENT-WS] Created UNKNOWN_FACE alert notification for session={}", sessionId);
        } catch (Exception e) {
            log.error("[AGENT-WS] Failed to save unknown-face alert: {}", e.getMessage(), e);
        }
    }

    private void handleCameraStatus(WebSocketSession session, Map<String, Object> data) {
        if (!Boolean.TRUE.equals(session.getAttributes().get(ATTR_AUTHENTICATED))) {
            return;
        }
        String status  = (String) data.getOrDefault("status", "UNKNOWN");
        String message = (String) data.getOrDefault("message", "");
        log.info("[AGENT-WS] CAMERA_STATUS: status={} message={}", status, message);
        sessionManager.updateCameraStatus(status, message);
    }

    private void handleAttendanceComplete(WebSocketSession session, Map<String, Object> data) {
        if (!Boolean.TRUE.equals(session.getAttributes().get(ATTR_AUTHENTICATED))) {
            return;
        }
        Object sessionIdObj = data.get("sessionId");
        Object countObj     = data.get("recognizedCount");
        log.info("[AGENT-WS] ATTENDANCE_COMPLETE from agent: session={}, recognizedCount={}", sessionIdObj, countObj);
        sessionManager.updateCameraStatus("CLOSED", "Attendance job completed by agent");
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

    private void sendJson(WebSocketSession session, Map<String, Object> payload) {
        if (session != null && session.isOpen()) {
            synchronized (session) {
                if (session.isOpen()) {
                    try {
                        session.sendMessage(new TextMessage(objectMapper.writeValueAsString(payload)));
                    } catch (IllegalStateException | IOException e) {
                        log.warn("[AGENT-WS] Failed to send message to session {}: {}", session.getId(), e.getMessage());
                    }
                }
            }
        }
    }
}
