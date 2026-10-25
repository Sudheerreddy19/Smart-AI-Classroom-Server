package com.finalYear.smartClassRoom.ai.tools;

import com.finalYear.smartClassRoom.entity.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Dispatches tool invocations by tool name with parameter parsing and safety guarantees.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ClassroomToolDispatcher {

    private final ClassroomToolsService toolsService;
    private final ClassroomToolDefinitions toolDefinitions;

    public List<Map<String, Object>> getToolDefinitions() {
        return toolDefinitions.getToolDefinitions();
    }

    public Map<String, Object> dispatch(String toolName, Map<String, Object> args, User user) {
        if (toolName == null || toolName.isBlank()) {
            return Map.of("status", "ERROR", "message", "Tool name cannot be empty");
        }

        Map<String, Object> arguments = (args != null) ? args : Collections.emptyMap();

        log.info("Dispatching tool='{}' requested by user='{}' ({})",
                toolName,
                user != null ? user.getEmail() : "anonymous",
                user != null ? user.getRole() : "NONE");

        try {
            return switch (toolName) {
                case "getCurrentSensorReadings" -> {
                    Long classroomId = parseLong(arguments.get("classroomId"));
                    yield toolsService.getCurrentSensorReadings(classroomId);
                }
                case "getDeviceStatus" -> {
                    Long classroomId = parseLong(arguments.get("classroomId"));
                    String deviceId = (String) arguments.get("deviceId");
                    yield toolsService.getDeviceStatus(classroomId, deviceId);
                }
                case "getCurrentTimetable" -> {
                    Long classroomId = parseLong(arguments.get("classroomId"));
                    yield toolsService.getCurrentTimetable(classroomId);
                }
                case "getUpcomingClasses" -> {
                    Long classroomId = parseLong(arguments.get("classroomId"));
                    Integer limit = parseInteger(arguments.get("limit"));
                    yield toolsService.getUpcomingClasses(classroomId, limit);
                }
                case "getAttendanceSummary" -> {
                    Long classroomId = parseLong(arguments.get("classroomId"));
                    String date = (String) arguments.get("date");
                    yield toolsService.getAttendanceSummary(classroomId, date);
                }
                case "controlDevice" -> {
                    Long classroomId = parseLong(arguments.get("classroomId"));
                    String target = (String) arguments.get("target");
                    String action = (String) arguments.get("action");
                    boolean confirmed = parseBoolean(arguments.get("confirmed"));
                    yield toolsService.executeDeviceControl(classroomId, target, action, confirmed, user);
                }
                default -> Map.of(
                        "status", "ERROR",
                        "message", "Unknown tool name: " + toolName
                );
            };
        } catch (Exception e) {
            log.error("Error executing tool '{}': {}", toolName, e.getMessage(), e);
            return Map.of(
                    "status", "ERROR",
                    "message", "Failed to execute tool '" + toolName + "': " + e.getMessage()
            );
        }
    }

    private Long parseLong(Object obj) {
        if (obj == null) return null;
        if (obj instanceof Number) return ((Number) obj).longValue();
        try {
            return Long.parseLong(obj.toString().trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Integer parseInteger(Object obj) {
        if (obj == null) return null;
        if (obj instanceof Number) return ((Number) obj).intValue();
        try {
            return Integer.parseInt(obj.toString().trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private boolean parseBoolean(Object obj) {
        if (obj == null) return false;
        if (obj instanceof Boolean) return (Boolean) obj;
        return Boolean.parseBoolean(obj.toString().trim());
    }
}
