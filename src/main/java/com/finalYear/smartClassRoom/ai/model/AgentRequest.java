package com.finalYear.smartClassRoom.ai.model;

import com.finalYear.smartClassRoom.entity.User;
import lombok.*;

import java.util.List;
import java.util.Map;

/**
 * Standardised request passed to every AI Agent.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentRequest {

    private Long      userId;
    private User.Role role;
    private String    prompt;
    private Long      sessionId;
    private Long      classroomId;

    /** Optional subject context (e.g., "Data Structures", "DBMS") */
    private String    subjectContext;

    /** Optional conversation history for multi-turn sessions */
    private List<Map<String, String>> conversationHistory;

    /** Extra metadata (e.g., student ID for personalised queries) */
    private Map<String, Object> metadata;
}
