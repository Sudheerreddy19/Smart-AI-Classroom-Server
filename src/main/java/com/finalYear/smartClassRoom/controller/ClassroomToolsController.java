package com.finalYear.smartClassRoom.controller;

import com.finalYear.smartClassRoom.ai.tools.ClassroomToolDispatcher;
import com.finalYear.smartClassRoom.ai.tools.ClassroomToolsService;
import com.finalYear.smartClassRoom.entity.User;
import com.finalYear.smartClassRoom.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Controller exposing JARVIS Live Classroom Tools.
 * Supports both query-parameter and path-variable bindings for maximum frontend interoperability.
 */
@RestController
@RequestMapping("/api/ai/tools")
@RequiredArgsConstructor
@Slf4j
@CrossOrigin(origins = "*")
public class ClassroomToolsController {

    private final ClassroomToolsService toolsService;
    private final ClassroomToolDispatcher dispatcher;
    private final UserRepository userRepository;

    @GetMapping("/definitions")
    public ResponseEntity<List<Map<String, Object>>> getDefinitions() {
        return ResponseEntity.ok(dispatcher.getToolDefinitions());
    }

    @GetMapping(value = {"/sensors", "/sensors/{classroomId}"})
    public ResponseEntity<Map<String, Object>> getSensorReadings(
            @PathVariable(required = false) Long classroomId,
            @RequestParam(required = false) Long queryClassroomId) {
        Long id = classroomId != null ? classroomId : queryClassroomId;
        return ResponseEntity.ok(toolsService.getCurrentSensorReadings(id));
    }

    @GetMapping(value = {"/devices", "/devices/{classroomId}"})
    public ResponseEntity<Map<String, Object>> getDeviceStatus(
            @PathVariable(required = false) Long classroomId,
            @RequestParam(required = false) Long queryClassroomId,
            @RequestParam(required = false) String deviceId) {
        Long id = classroomId != null ? classroomId : queryClassroomId;
        return ResponseEntity.ok(toolsService.getDeviceStatus(id, deviceId));
    }

    @GetMapping(value = {"/current-timetable", "/timetable", "/timetable/{classroomId}"})
    public ResponseEntity<Map<String, Object>> getCurrentTimetable(
            @PathVariable(required = false) Long classroomId,
            @RequestParam(required = false) Long queryClassroomId) {
        Long id = classroomId != null ? classroomId : queryClassroomId;
        return ResponseEntity.ok(toolsService.getCurrentTimetable(id));
    }

    @GetMapping(value = {"/upcoming-classes", "/upcoming-classes/{classroomId}"})
    public ResponseEntity<Map<String, Object>> getUpcomingClasses(
            @PathVariable(required = false) Long classroomId,
            @RequestParam(required = false) Long queryClassroomId,
            @RequestParam(defaultValue = "5") Integer limit) {
        Long id = classroomId != null ? classroomId : queryClassroomId;
        return ResponseEntity.ok(toolsService.getUpcomingClasses(id, limit));
    }

    @GetMapping(value = {"/attendance-summary", "/attendance", "/attendance/{classroomId}"})
    public ResponseEntity<Map<String, Object>> getAttendanceSummary(
            @PathVariable(required = false) Long classroomId,
            @RequestParam(required = false) Long queryClassroomId,
            @RequestParam(required = false) String date) {
        Long id = classroomId != null ? classroomId : queryClassroomId;
        return ResponseEntity.ok(toolsService.getAttendanceSummary(id, date));
    }

    @PostMapping("/control")
    public ResponseEntity<Map<String, Object>> controlDevice(@RequestBody Map<String, Object> body) {
        User currentUser = resolveCurrentUser();
        Long classroomId = parseLong(body.get("classroomId"));
        String target = (String) body.get("target");
        String action = (String) body.get("action");
        boolean confirmed = parseBoolean(body.get("confirmed"));

        Map<String, Object> result = toolsService.executeDeviceControl(classroomId, target, action, confirmed, currentUser);
        if ("FORBIDDEN".equals(result.get("status"))) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(result);
        }
        return ResponseEntity.ok(result);
    }

    @PostMapping("/execute")
    public ResponseEntity<Map<String, Object>> executeTool(@RequestBody Map<String, Object> request) {
        User currentUser = resolveCurrentUser();
        String toolName = (String) request.get("tool");
        @SuppressWarnings("unchecked")
        Map<String, Object> arguments = (Map<String, Object>) request.get("arguments");

        Map<String, Object> result = dispatcher.dispatch(toolName, arguments, currentUser);
        if ("FORBIDDEN".equals(result.get("status"))) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(result);
        }
        return ResponseEntity.ok(result);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────
    private User resolveCurrentUser() {
        try {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth != null && auth.getPrincipal() instanceof User u) {
                return u;
            }
            if (auth != null && auth.getName() != null && !auth.getName().equals("anonymousUser")) {
                return userRepository.findByEmail(auth.getName()).orElse(null);
            }
        } catch (Exception ignored) {}
        return null;
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

    private boolean parseBoolean(Object obj) {
        if (obj == null) return false;
        if (obj instanceof Boolean) return (Boolean) obj;
        return Boolean.parseBoolean(obj.toString().trim());
    }
}
