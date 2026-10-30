package com.finalYear.smartClassRoom.ai.decision;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * Detailed audit record and outcome of an individual sensor automation rule evaluation.
 */
public record AutomationDecisionResult(
        String ruleName,               // e.g. "TEMPERATURE_FAN_CONTROL", "LIGHT_INTENSITY_CONTROL", "MOTION_INACTIVITY_CONTROL", "EMERGENCY_PROTECTION"
        String targetDevice,           // "FAN", "LIGHT", "EMERGENCY"
        String requestedAction,        // "ON", "OFF", "RETAIN", "ALERT"
        AutomationDecisionStatus status,
        String previousState,
        String newState,
        String reason,
        Map<String, Object> sensorSnapshot,
        LocalDateTime timestamp,
        boolean executed
) {}
