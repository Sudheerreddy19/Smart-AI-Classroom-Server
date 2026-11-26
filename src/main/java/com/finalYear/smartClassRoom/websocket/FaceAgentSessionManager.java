package com.finalYear.smartClassRoom.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.*;

@Component
@Slf4j
@RequiredArgsConstructor
public class FaceAgentSessionManager {

    private final ObjectMapper objectMapper;

    // Active session for the connected Python AI agent
    private volatile WebSocketSession agentSession;
    private volatile String agentId;
    private volatile Instant lastHeartbeat;

    @Getter
    private volatile String cameraStatus = "UNKNOWN";

    @Getter
    private volatile String cameraMessage = "";

    // Pending capture jobs: jobId -> CompletableFuture<Map<String, Object>>
    private final ConcurrentHashMap<String, CompletableFuture<Map<String, Object>>> pendingJobs = new ConcurrentHashMap<>();

    public synchronized void registerSession(WebSocketSession session, String agentId) {
        if (this.agentSession != null && this.agentSession.isOpen() && !this.agentSession.getId().equals(session.getId())) {
            log.warn("[AGENT-WS] Replacing existing active agent session {} with new session {}",
                    this.agentSession.getId(), session.getId());
            try {
                this.agentSession.close();
            } catch (IOException e) {
                log.warn("[AGENT-WS] Error closing old session: {}", e.getMessage());
            }
        }
        this.agentSession = session;
        this.agentId = agentId;
        this.lastHeartbeat = Instant.now();
        log.info("[AGENT-WS] Python AI Agent registered successfully: agentId={}, sessionId={}", agentId, session.getId());
    }

    public synchronized void removeSession(WebSocketSession session) {
        if (this.agentSession != null && this.agentSession.getId().equals(session.getId())) {
            log.warn("[AGENT-WS] Python AI Agent disconnected: agentId={}, sessionId={}", agentId, session.getId());
            this.agentSession = null;
            this.agentId = null;
            this.cameraStatus = "DISCONNECTED";
            this.cameraMessage = "Python agent disconnected";

            // Fail all pending jobs with agent disconnection error
            if (!pendingJobs.isEmpty()) {
                log.warn("[AGENT-WS] Cancelling {} pending jobs due to agent disconnection", pendingJobs.size());
                pendingJobs.forEach((jobId, future) ->
                        future.completeExceptionally(new IllegalStateException("Python agent disconnected during capture job"))
                );
                pendingJobs.clear();
            }
        }
    }

    public boolean isAgentConnected() {
        return agentSession != null && agentSession.isOpen();
    }

    public String getConnectedAgentId() {
        return agentId;
    }

    public Instant getLastHeartbeat() {
        return lastHeartbeat;
    }

    public void updateHeartbeat() {
        this.lastHeartbeat = Instant.now();
    }

    public void updateCameraStatus(String status, String message) {
        this.cameraStatus = status != null ? status : "UNKNOWN";
        this.cameraMessage = message != null ? message : "";
        log.debug("[AGENT-WS] Camera status updated: {} — {}", this.cameraStatus, this.cameraMessage);
    }

    public CompletableFuture<Map<String, Object>> dispatchJob(Map<String, Object> jobPayload, long timeoutSeconds) {
        WebSocketSession session = this.agentSession;
        if (session == null || !session.isOpen()) {
            throw new IllegalStateException("Face recognition service is not connected. " +
                    "Please ensure the Python Class AI service is running on your classroom laptop.");
        }

        String jobId = (String) jobPayload.get("jobId");
        if (jobId == null || jobId.isBlank()) {
            throw new IllegalArgumentException("jobId is required in jobPayload");
        }

        CompletableFuture<Map<String, Object>> future = new CompletableFuture<>();
        pendingJobs.put(jobId, future);

        try {
            String json = objectMapper.writeValueAsString(jobPayload);
            synchronized (session) {
                if (!session.isOpen()) {
                    throw new IllegalStateException("WebSocket session closed before job dispatch");
                }
                session.sendMessage(new TextMessage(json));
            }
            log.info("[AGENT-WS] Dispatched CAPTURE_JOB to agent: jobId={}, studentDbId={}",
                    jobId, jobPayload.get("studentDbId"));
        } catch (Exception e) {
            pendingJobs.remove(jobId);
            log.error("[AGENT-WS] Failed to send job to agent: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to dispatch capture job to Python agent: " + e.getMessage(), e);
        }

        // Auto-remove job after timeout to prevent memory leak
        CompletableFuture.delayedExecutor(timeoutSeconds + 5, TimeUnit.SECONDS).execute(() -> {
            CompletableFuture<Map<String, Object>> timedOut = pendingJobs.remove(jobId);
            if (timedOut != null && !timedOut.isDone()) {
                timedOut.completeExceptionally(new TimeoutException("Capture job " + jobId + " timed out after " + timeoutSeconds + " seconds."));
            }
        });

        return future;
    }

    public void completeJob(String jobId, Map<String, Object> result) {
        CompletableFuture<Map<String, Object>> future = pendingJobs.remove(jobId);
        if (future != null) {
            log.info("[AGENT-WS] Completing capture job: jobId={}, success={}", jobId, result.get("success"));
            future.complete(result);
        } else {
            log.warn("[AGENT-WS] Received result for unknown or already completed job: jobId={}", jobId);
        }
    }

    /**
     * Fire-and-forget command to the Python agent over WebSocket.
     * Used for ATTENDANCE_START, ATTENDANCE_PAUSE, ATTENDANCE_RESUME, ATTENDANCE_STOP.
     */
    public void sendCommand(Map<String, Object> payload) {
        WebSocketSession session = this.agentSession;
        if (session == null || !session.isOpen()) {
            throw new IllegalStateException(
                    "Face recognition service is not connected. " +
                    "Please ensure the Python Class AI service is running on your classroom laptop.");
        }
        try {
            String json = objectMapper.writeValueAsString(payload);
            synchronized (session) {
                if (!session.isOpen()) {
                    throw new IllegalStateException("WebSocket session closed before command dispatch");
                }
                session.sendMessage(new TextMessage(json));
            }
            log.info("[AGENT-WS] Sent command type={} to agent", payload.get("type"));
        } catch (Exception e) {
            log.error("[AGENT-WS] Failed to send command to agent: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to send command to Python agent: " + e.getMessage(), e);
        }
    }
}
