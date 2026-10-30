package com.finalYear.smartClassRoom.ai.decision;

/**
 * Standard decision results returned by the Spring Boot Classroom Automation Engine.
 */
public enum AutomationDecisionStatus {
    ACTION_REQUIRED,
    NO_ACTION_REQUIRED,
    INSUFFICIENT_DATA,
    COOLDOWN_ACTIVE,
    EXECUTION_FAILED,
    EXECUTION_SUCCESS
}
