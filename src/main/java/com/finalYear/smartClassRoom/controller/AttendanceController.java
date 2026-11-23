package com.finalYear.smartClassRoom.controller;

import com.finalYear.smartClassRoom.dto.request.AttendanceSessionRequest;
import com.finalYear.smartClassRoom.dto.request.FaceAttendanceRequest;
import com.finalYear.smartClassRoom.dto.request.MarkAttendanceRequest;
import com.finalYear.smartClassRoom.dto.response.AttendanceResponse;
import com.finalYear.smartClassRoom.dto.response.AttendanceSessionResponse;
import com.finalYear.smartClassRoom.dto.response.FaceAttendanceResult;
import com.finalYear.smartClassRoom.entity.User;
import com.finalYear.smartClassRoom.service.AttendanceService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/attendance")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class AttendanceController {

    private final AttendanceService attendanceService;


    @PostMapping("/session")
    public ResponseEntity<AttendanceSessionResponse> createSession(
            @Valid @RequestBody AttendanceSessionRequest request) {

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(attendanceService.createSession(request));
    }


    @PutMapping("/session/{sessionId}/close")
    public ResponseEntity<AttendanceSessionResponse> closeSession(
            @PathVariable Long sessionId) {

        return ResponseEntity.ok(
                attendanceService.closeSession(sessionId));
    }


    @PostMapping("/mark")
    public ResponseEntity<AttendanceResponse> markAttendance(
            @Valid @RequestBody MarkAttendanceRequest request) {

        return ResponseEntity.ok(
                attendanceService.markAttendance(request));
    }


    @GetMapping("/session/{sessionId}")
    public ResponseEntity<List<AttendanceResponse>> getSessionAttendance(
            @PathVariable Long sessionId) {

        return ResponseEntity.ok(
                attendanceService.getSessionAttendance(sessionId));
    }


    @GetMapping("/today")
    public ResponseEntity<List<AttendanceSessionResponse>> getTodaySessions(
            @AuthenticationPrincipal User caller) {

        return ResponseEntity.ok(
                attendanceService.getTodaySessions());
    }


    /**
     * POST /api/attendance/face-mark
     * Calls Python face-recognition service to identify a student from the webcam,
     * then marks them PRESENT in the given session.
     */
    @PostMapping("/face-mark")
    public ResponseEntity<FaceAttendanceResult> faceMarkAttendance(
            @Valid @RequestBody FaceAttendanceRequest request,
            @AuthenticationPrincipal User caller) {

        return ResponseEntity.ok(
                attendanceService.faceMarkAttendance(request, caller));
    }

    /**
     * POST /api/attendance/face-mark-multi
     * Detects ALL faces in the browser webcam frame and marks attendance
     * for every recognised student simultaneously.
     */
    @PostMapping("/face-mark-multi")
    public ResponseEntity<List<FaceAttendanceResult>> faceMarkAttendanceMulti(
            @Valid @RequestBody FaceAttendanceRequest request,
            @AuthenticationPrincipal User caller) {

        return ResponseEntity.ok(
                attendanceService.faceMarkAttendanceMulti(request, caller));
    }



    /**
     * GET /api/attendance/report?departmentId=X&semesterId=Y&subjectId=Z&from=YYYY-MM-DD&to=YYYY-MM-DD
     * Returns student-wise attendance percentage report.
     */
    @GetMapping("/report")
    public ResponseEntity<List<Map<String, Object>>> getAttendanceReport(
            @RequestParam(required = false) Long departmentId,
            @RequestParam(required = false) Long semesterId,
            @RequestParam(required = false) Long subjectId,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to) {

        LocalDate dateFrom = from != null ? LocalDate.parse(from) : LocalDate.now().minusDays(30);
        LocalDate dateTo   = to   != null ? LocalDate.parse(to)   : LocalDate.now();

        return ResponseEntity.ok(
                attendanceService.getAttendanceReport(departmentId, semesterId, subjectId, dateFrom, dateTo));
    }


    /**
     * GET /api/attendance/sessions?departmentId=X&from=YYYY-MM-DD&to=YYYY-MM-DD
     * Returns all sessions with present/absent counts for the report page.
     */
    @GetMapping("/sessions")
    public ResponseEntity<List<AttendanceSessionResponse>> getSessions(
            @RequestParam(required = false) Long departmentId,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to) {

        LocalDate dateFrom = from != null ? LocalDate.parse(from) : LocalDate.now().minusDays(30);
        LocalDate dateTo   = to   != null ? LocalDate.parse(to)   : LocalDate.now();

        return ResponseEntity.ok(
                attendanceService.getSessionsInRange(departmentId, dateFrom, dateTo));
    }

    // ── Voice-Triggered Face Attendance Endpoints ─────────────────────────────

    /**
     * POST /api/attendance/face/start-session
     *
     * Creates an attendance session and commands the Python agent via WebSocket
     * to open the webcam and begin recognition.
     */
    @PostMapping("/face/start-session")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN','HOD','TEACHER')")
    public ResponseEntity<Map<String, Object>> startFaceAttendanceSession(
            @Valid @RequestBody AttendanceSessionRequest request) {
        Map<String, Object> result = attendanceService.startFaceAttendanceSession(request);
        String status = (String) result.getOrDefault("status", "ERROR");
        if ("AGENT_OFFLINE".equals(status)) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(result);
        }
        if ("ERROR".equals(status)) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(result);
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(result);
    }

    /**
     * POST /api/attendance/face/control
     *
     * Dispatches PAUSE, RESUME, or STOP action to the Python agent over WebSocket.
     * On STOP, marks the database session COMPLETED and calculates counts.
     */
    @PostMapping("/face/control")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN','HOD','TEACHER')")
    public ResponseEntity<Map<String, Object>> controlFaceAttendanceSession(
            @Valid @RequestBody FaceControlRequest request) {
        Map<String, Object> result = attendanceService.controlFaceAttendanceSession(
                request.getSessionId(),
                request.getAgentJobId(),
                request.getAction()
        );
        String status = (String) result.getOrDefault("status", "ERROR");
        if ("ERROR".equals(status)) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(result);
        }
        return ResponseEntity.ok(result);
    }

    @Data
    public static class FaceControlRequest {
        @NotNull(message = "sessionId is required")
        private Long sessionId;

        private String agentJobId;

        @NotBlank(message = "action is required: PAUSE, RESUME, or STOP")
        private String action;
    }
}