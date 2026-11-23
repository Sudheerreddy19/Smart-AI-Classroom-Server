package com.finalYear.smartClassRoom.service;

import com.finalYear.smartClassRoom.dto.request.AttendanceSessionRequest;
import com.finalYear.smartClassRoom.dto.request.FaceAttendanceRequest;
import com.finalYear.smartClassRoom.dto.request.MarkAttendanceRequest;
import com.finalYear.smartClassRoom.dto.response.AttendanceResponse;
import com.finalYear.smartClassRoom.dto.response.AttendanceSessionResponse;
import com.finalYear.smartClassRoom.dto.response.FaceAttendanceResult;
import com.finalYear.smartClassRoom.entity.User;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

public interface AttendanceService {

    AttendanceSessionResponse createSession(AttendanceSessionRequest request);

    AttendanceSessionResponse closeSession(Long sessionId);

    AttendanceResponse markAttendance(MarkAttendanceRequest request);

    List<AttendanceResponse> getSessionAttendance(Long sessionId);

    List<AttendanceSessionResponse> getTodaySessions();

    /** Calls Python face service to recognize a student, then marks them PRESENT */
    FaceAttendanceResult faceMarkAttendance(FaceAttendanceRequest request, User caller);

    /** Mark attendance for ALL faces detected in a single webcam frame. */
    List<FaceAttendanceResult> faceMarkAttendanceMulti(FaceAttendanceRequest request, User caller);

    /** Student-wise attendance % report filtered by dept/semester/subject/date range */
    List<Map<String, Object>> getAttendanceReport(Long departmentId, Long semesterId,
                                                   Long subjectId,
                                                   LocalDate from, LocalDate to);

    /** All sessions in a date range, optionally filtered by department */
    List<AttendanceSessionResponse> getSessionsInRange(Long departmentId,
                                                        LocalDate from, LocalDate to);

    // ── Voice-triggered face attendance methods ──────────────────────────────

    /**
     * Start a face-recognition attendance session:
     * 1. Creates an active AttendanceSession in the database.
     * 2. Dispatches ATTENDANCE_START to the authenticated Python AI agent via WebSocket.
     */
    Map<String, Object> startFaceAttendanceSession(AttendanceSessionRequest request);

    /**
     * Send PAUSE / RESUME / STOP commands to the Python AI agent via WebSocket.
     * On STOP, marks the database session COMPLETED and calculates counts.
     */
    Map<String, Object> controlFaceAttendanceSession(Long sessionId, String agentJobId, String action);

    /**
     * Mark a recognized student PRESENT from an incoming WebSocket RECOGNITION_EVENT.
     * Reuses the existing student repository and uk_session_student duplicate prevention.
     */
    FaceAttendanceResult markRecognizedStudent(Long sessionId, String rollNumber, Double confidence);

    /**
     * Mark a recognized student PRESENT using student database ID or rollNumber.
     */
    FaceAttendanceResult markRecognizedStudent(Long sessionId, Long studentDbId, String rollNumber, Double confidence);
}
