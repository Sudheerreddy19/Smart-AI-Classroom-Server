package com.finalYear.smartClassRoom.ai.tools;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Metadata and schema definitions for JARVIS Classroom Tools.
 * Follows function-calling schema conventions for AI models.
 */
@Component
public class ClassroomToolDefinitions {

    public List<Map<String, Object>> getToolDefinitions() {
        return List.of(
                Map.of(
                        "name", "getCurrentSensorReadings",
                        "description", "Fetches real-time sensor telemetry (temperature, humidity, light, gas leak, motion, room occupancy state) from the latest hardware reading.",
                        "parameters", Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "classroomId", Map.of("type", "integer", "description", "Optional classroom ID; defaults to active classroom.")
                                )
                        )
                ),
                Map.of(
                        "name", "getDeviceStatus",
                        "description", "Fetches online/offline status, device types, and last seen timestamps for classroom IoT devices.",
                        "parameters", Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "classroomId", Map.of("type", "integer", "description", "Optional classroom ID"),
                                        "deviceId", Map.of("type", "string", "description", "Optional specific device ID e.g. ESP32_01")
                                )
                        )
                ),
                Map.of(
                        "name", "getCurrentTimetable",
                        "description", "Fetches the class currently in session for a classroom, including subject, instructor, start and end times.",
                        "parameters", Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "classroomId", Map.of("type", "integer", "description", "Optional classroom ID")
                                )
                        )
                ),
                Map.of(
                        "name", "getUpcomingClasses",
                        "description", "Fetches upcoming classes scheduled for the remainder of today.",
                        "parameters", Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "classroomId", Map.of("type", "integer", "description", "Optional classroom ID"),
                                        "limit", Map.of("type", "integer", "description", "Maximum number of classes to return (default 5)")
                                )
                        )
                ),
                Map.of(
                        "name", "getAttendanceSummary",
                        "description", "Fetches AGGREGATE-ONLY attendance statistics (present count, absent count, attendance percentage). NEVER returns individual student PII.",
                        "parameters", Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "classroomId", Map.of("type", "integer", "description", "Optional classroom ID"),
                                        "date", Map.of("type", "string", "description", "Optional date in YYYY-MM-DD format (defaults to today)")
                                )
                        )
                ),
                Map.of(
                        "name", "controlDevice",
                        "description", "Controls classroom equipment (lights, fans, projector, all). Requires TEACHER or ADMIN role. Returns confirmation preview with safety checks if unconfirmed.",
                        "parameters", Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "classroomId", Map.of("type", "integer", "description", "Optional classroom ID"),
                                        "target", Map.of("type", "string", "description", "Target equipment: LIGHT, FAN, PROJECTOR, ALL, or device ID"),
                                        "action", Map.of("type", "string", "enum", List.of("ON", "OFF"), "description", "Action to perform"),
                                        "confirmed", Map.of("type", "boolean", "description", "Whether the action has been explicitly confirmed by the user")
                                ),
                                "required", List.of("target", "action")
                        )
                )
        );
    }
}
