package com.finalYear.smartClassRoom.service.impl;

import com.finalYear.smartClassRoom.dto.request.AttendanceSessionRequest;
import com.finalYear.smartClassRoom.dto.request.FaceAttendanceRequest;
import com.finalYear.smartClassRoom.dto.request.MarkAttendanceRequest;
import com.finalYear.smartClassRoom.dto.response.AttendanceResponse;
import com.finalYear.smartClassRoom.dto.response.AttendanceSessionResponse;
import com.finalYear.smartClassRoom.dto.response.FaceAttendanceResult;
import com.finalYear.smartClassRoom.entity.*;
import com.finalYear.smartClassRoom.exception.ResourceNotFoundException;
import com.finalYear.smartClassRoom.repository.*;
import com.finalYear.smartClassRoom.service.AttendanceService;
import com.finalYear.smartClassRoom.service.CurrentUserContextService;
import com.finalYear.smartClassRoom.service.NotificationService;
import com.finalYear.smartClassRoom.websocket.FaceAgentSessionManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class AttendanceServiceImpl implements AttendanceService {

    private final AttendanceSessionRepository attendanceSessionRepository;
    private final AttendanceRepository        attendanceRepository;
    private final ClassroomRepository         classroomRepository;
    private final SubjectRepository           subjectRepository;
    private final TeacherRepository           teacherRepository;
    private final StudentRepository           studentRepository;
    private final TimetableRepository         timetableRepository;
    private final StudentFaceRepository       studentFaceRepository;
    private final CurrentUserContextService   currentUserCtx;
    private final RestTemplate                restTemplate;
    private final FaceAgentSessionManager     faceAgentSessionManager;
    private final NotificationService         notificationService;

    @Value("${face.service.url:http://localhost:8000}")
    private String faceServiceUrl;

    @Override
    @Transactional
    public AttendanceSessionResponse createSession(AttendanceSessionRequest request) {

        Classroom classroom = classroomRepository.findById(request.getClassroomId())
                .orElseThrow(() -> new ResourceNotFoundException("Classroom", request.getClassroomId()));

        // Resolve subject, teacher, and timetable slot dynamically if not explicitly provided
        Timetable resolvedTimetable = null;
        if (request.getTimetableId() != null) {
            resolvedTimetable = timetableRepository.findById(request.getTimetableId()).orElse(null);
        }

        if (resolvedTimetable == null && (request.getSubjectId() == null || request.getTeacherId() == null)) {
            LocalDate today = request.getSessionDate() != null ? request.getSessionDate() : LocalDate.now();
            LocalTime now = request.getStartTime() != null ? request.getStartTime() : LocalTime.now();
            Timetable.DayOfWeek dow = switch (today.getDayOfWeek()) {
                case MONDAY -> Timetable.DayOfWeek.MONDAY;
                case TUESDAY -> Timetable.DayOfWeek.TUESDAY;
                case WEDNESDAY -> Timetable.DayOfWeek.WEDNESDAY;
                case THURSDAY -> Timetable.DayOfWeek.THURSDAY;
                case FRIDAY -> Timetable.DayOfWeek.FRIDAY;
                case SATURDAY -> Timetable.DayOfWeek.SATURDAY;
                default -> null;
            };

            if (dow != null) {
                List<Timetable> slots = timetableRepository.findByClassroomAndDayOfWeek(classroom, dow);
                resolvedTimetable = slots.stream()
                        .filter(Timetable::isActive)
                        .filter(t -> !now.isBefore(t.getStartTime()) && !now.isAfter(t.getEndTime()))
                        .findFirst()
                        .orElse(null);
                if (resolvedTimetable == null && !slots.isEmpty()) {
                    resolvedTimetable = slots.get(0);
                }
            }
        }

        Subject subject;
        if (request.getSubjectId() != null) {
            subject = subjectRepository.findById(request.getSubjectId())
                    .orElseThrow(() -> new ResourceNotFoundException("Subject", request.getSubjectId()));
        } else if (resolvedTimetable != null && resolvedTimetable.getSubject() != null) {
            subject = resolvedTimetable.getSubject();
        } else {
            // Find first active subject
            subject = subjectRepository.findAll().stream().findFirst()
                    .orElseThrow(() -> new RuntimeException("No active subject found in the database."));
        }

        Teacher teacher;
        if (request.getTeacherId() != null) {
            teacher = teacherRepository.findById(request.getTeacherId())
                    .orElseThrow(() -> new ResourceNotFoundException("Teacher", request.getTeacherId()));
        } else if (resolvedTimetable != null && resolvedTimetable.getTeacher() != null) {
            teacher = resolvedTimetable.getTeacher();
        } else {
            // Check authenticated teacher or first teacher
            teacher = teacherRepository.findAll().stream().findFirst()
                    .orElseThrow(() -> new RuntimeException("No active teacher found in the database."));
        }

        LocalDate sessionDate = request.getSessionDate() != null ? request.getSessionDate() : LocalDate.now();
        LocalTime startTime = request.getStartTime() != null ? request.getStartTime() : LocalTime.now();
        LocalTime endTime = resolvedTimetable != null && resolvedTimetable.getEndTime() != null
                ? resolvedTimetable.getEndTime()
                : startTime.plusHours(1); // default 1 hour period

        // Calculate dynamic total students from actual class roster
        int totalStudents = 0;
        if (resolvedTimetable != null && resolvedTimetable.getSection() != null
                && resolvedTimetable.getSemester() != null
                && resolvedTimetable.getTeacher() != null
                && resolvedTimetable.getTeacher().getDepartment() != null) {
            totalStudents = studentRepository.findByDepartment_IdAndSemester_IdAndSection_IdAndActiveTrue(
                    resolvedTimetable.getTeacher().getDepartment().getId(),
                    resolvedTimetable.getSemester().getId(),
                    resolvedTimetable.getSection().getId()
            ).size();
        } else if (resolvedTimetable != null && resolvedTimetable.getSemester() != null
                && resolvedTimetable.getTeacher() != null
                && resolvedTimetable.getTeacher().getDepartment() != null) {
            totalStudents = studentRepository.findByDepartmentAndSemester(
                    resolvedTimetable.getTeacher().getDepartment(),
                    resolvedTimetable.getSemester()
            ).size();
        }

        if (totalStudents == 0) {
            if (teacher.getDepartment() != null) {
                totalStudents = (int) studentRepository.countByDepartment_Id(teacher.getDepartment().getId());
            }
            if (totalStudents == 0) {
                totalStudents = (int) studentRepository.count();
            }
        }

        AttendanceSession session = AttendanceSession.builder()
                .timetable(resolvedTimetable)
                .classroom(classroom)
                .subject(subject)
                .teacher(teacher)
                .sessionDate(sessionDate)
                .startTime(startTime)
                .endTime(endTime)
                .status(AttendanceSession.SessionStatus.ACTIVE)
                .totalStudents(totalStudents)
                .presentCount(0)
                .createdAt(LocalDateTime.now())
                .build();

        AttendanceSession saved = attendanceSessionRepository.save(session);
        AttendanceSessionResponse resp = toSessionResponse(saved);
        resp.setTotalStudents(totalStudents);
        return resp;
    }

    @Override
    @Transactional
    public AttendanceSessionResponse closeSession(Long sessionId) {

        AttendanceSession session = attendanceSessionRepository.findById(sessionId)
                .orElseThrow(() -> new ResourceNotFoundException("Attendance Session", sessionId));

        session.setEndTime(LocalTime.now());
        session.setStatus(AttendanceSession.SessionStatus.COMPLETED);

        // 1. Identify all enrolled students for this class roster
        List<Student> enrolledStudents = new ArrayList<>();
        if (session.getTimetable() != null && session.getTimetable().getSection() != null
                && session.getTimetable().getSemester() != null
                && session.getTeacher() != null && session.getTeacher().getDepartment() != null) {
            enrolledStudents = studentRepository.findByDepartment_IdAndSemester_IdAndSection_IdAndActiveTrue(
                    session.getTeacher().getDepartment().getId(),
                    session.getTimetable().getSemester().getId(),
                    session.getTimetable().getSection().getId()
            );
        } else if (session.getTeacher() != null && session.getTeacher().getDepartment() != null) {
            enrolledStudents = studentRepository.findByDepartment(session.getTeacher().getDepartment());
        }

        if (enrolledStudents.isEmpty()) {
            enrolledStudents = studentRepository.findByActiveTrue();
        }

        // 2. Identify students who already have an attendance record for this session
        List<Attendance> existingRecords = attendanceRepository.findByAttendanceSession(session);
        Set<Long> recordedStudentIds = existingRecords.stream()
                .map(a -> a.getStudent().getId())
                .collect(Collectors.toSet());

        // 3. Mark all remaining enrolled students as ABSENT
        List<Attendance> absentRecords = new ArrayList<>();
        for (Student student : enrolledStudents) {
            if (!recordedStudentIds.contains(student.getId())) {
                Attendance absent = Attendance.builder()
                        .attendanceSession(session)
                        .student(student)
                        .status(Attendance.AttendanceStatus.ABSENT)
                        .method(Attendance.MarkingMethod.MANUAL)
                        .confidenceScore(0.0)
                        .markedAt(LocalDateTime.now())
                        .build();
                absentRecords.add(absent);
            }
        }

        if (!absentRecords.isEmpty()) {
            attendanceRepository.saveAll(absentRecords);
            log.info("[ATTENDANCE] Marked {} absentees for session #{}", absentRecords.size(), sessionId);
        }

        // Recalculate counts
        long presentCount = attendanceRepository.countByAttendanceSessionAndStatus(
                session, Attendance.AttendanceStatus.PRESENT);
        session.setPresentCount((int) presentCount);
        session.setTotalStudents(enrolledStudents.size());
        AttendanceSession saved = attendanceSessionRepository.save(session);

        // 4. Send comprehensive Attendance Report Notification to ADMIN and TEACHER
        try {
            int absentCount = (int) (saved.getTotalStudents() - presentCount);
            String subjectName = saved.getSubject() != null ? saved.getSubject().getName() : "Subject";
            String roomNumber = saved.getClassroom() != null ? saved.getClassroom().getRoomNumber() : "Room";
            String teacherName = saved.getTeacher() != null ? saved.getTeacher().getFullName() : "Teacher";

            com.finalYear.smartClassRoom.dto.request.NotificationRequest notif = new com.finalYear.smartClassRoom.dto.request.NotificationRequest();
            notif.setTitle("Attendance Report: " + subjectName + " (" + roomNumber + ")");
            notif.setMessage(String.format("Session #%d completed by %s. Total: %d, Present: %d, Absent: %d.",
                    sessionId, teacherName, saved.getTotalStudents(), presentCount, absentCount));
            notif.setType(Notification.NotificationType.INFO);
            notif.setTargetRole(User.Role.ADMIN);
            notificationService.create(notif);
            log.info("[ATTENDANCE] Sent attendance report notification to ADMIN for session #{}", sessionId);
        } catch (Exception e) {
            log.warn("[ATTENDANCE] Could not create attendance report notification: {}", e.getMessage());
        }

        return toSessionResponse(saved);
    }

    @Override
    @Transactional
    public AttendanceResponse markAttendance(MarkAttendanceRequest request) {

        AttendanceSession session = attendanceSessionRepository.findById(request.getSessionId())
                .orElseThrow(() -> new ResourceNotFoundException("Attendance Session", request.getSessionId()));

        Student student = studentRepository.findById(request.getStudentId())
                .orElseThrow(() -> new ResourceNotFoundException("Student", request.getStudentId()));

        Attendance attendance = Attendance.builder()
                .attendanceSession(session)
                .student(student)
                .status(request.getStatus())
                .method(request.getMethod())
                .confidenceScore(request.getConfidenceScore())
                .markedAt(LocalDateTime.now())
                .build();

        return toAttendanceResponse(attendanceRepository.save(attendance));
    }

    @Override
    public List<AttendanceResponse> getSessionAttendance(Long sessionId) {

        AttendanceSession session = attendanceSessionRepository.findById(sessionId)
                .orElseThrow(() -> new ResourceNotFoundException("Attendance Session", sessionId));

        return attendanceRepository.findByAttendanceSession(session)
                .stream()
                .map(this::toAttendanceResponse)
                .toList();
    }

    @Override
    public List<AttendanceSessionResponse> getTodaySessions() {
        java.time.LocalDate today = java.time.LocalDate.now();
        User.Role role = currentUserCtx.getCallerRole();

        if (role == User.Role.HOD || role == User.Role.TEACHER) {
            Long deptId = currentUserCtx.getCallerDepartmentId();
            if (deptId != null) {
                return attendanceSessionRepository
                        .findByTeacher_Department_IdAndSessionDate(deptId, today)
                        .stream().map(this::toSessionResponse).toList();
            }
        }
        return attendanceSessionRepository.findBySessionDate(today)
                .stream().map(this::toSessionResponse).toList();
    }

    // ── Face-recognition attendance ───────────────────────────────────────────

    @Override
    @Transactional
    public FaceAttendanceResult faceMarkAttendance(FaceAttendanceRequest request, User caller) {

        AttendanceSession session = attendanceSessionRepository.findById(request.getSessionId())
                .orElseThrow(() -> new ResourceNotFoundException("AttendanceSession", request.getSessionId()));

        if (session.getStatus() != AttendanceSession.SessionStatus.ACTIVE) {
            return FaceAttendanceResult.builder()
                    .success(false).code("SESSION_CLOSED")
                    .message("This attendance session is already closed.").build();
        }

        Map<String, Object> pyBody = new HashMap<>();
        // Always derive subject/teacher from the stored session — never trust empty frontend labels
        String subjectName = session.getSubject() != null ? session.getSubject().getName() : "Unknown";
        String teacherId   = session.getTeacher() != null
                ? (session.getTeacher().getEmployeeId() != null
                    ? session.getTeacher().getEmployeeId()
                    : String.valueOf(session.getTeacher().getId()))
                : "teacher-" + session.getId();
        pyBody.put("subject",    subjectName);
        pyBody.put("session",    "session-" + session.getId());
        pyBody.put("teacher_id", teacherId);
        // Forward browser webcam frame so Python skips opening its own camera
        if (request.getImageBase64() != null && !request.getImageBase64().isBlank()) {
            pyBody.put("image_base64", request.getImageBase64());
        }

        String pyUrl = faceServiceUrl + "/attendance";



        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> pyResp = restTemplate.postForObject(pyUrl, pyBody, Map.class);

            if (pyResp == null) {
                return FaceAttendanceResult.builder().success(false).code("NO_RESPONSE")
                        .message("No response from Python face service.").build();
            }

            String code = (String) pyResp.getOrDefault("code", "UNKNOWN");

            if (!"MARKED".equals(code)) {
                return FaceAttendanceResult.builder()
                        .success(false).code(code)
                        .message((String) pyResp.getOrDefault("message", code)).build();
            }

            String rollNumber  = (String) pyResp.get("student_id");
            String studentName = (String) pyResp.getOrDefault("student_name", "");

            Student student = studentRepository.findByRollNumber(rollNumber).orElse(null);

            if (student == null) {
                log.warn("Face recognised roll={} but not found in Spring Boot DB", rollNumber);
                return FaceAttendanceResult.builder()
                        .success(true).code("MARKED_PYTHON_ONLY")
                        .studentId(rollNumber).studentName(studentName)
                        .message("Face recognised but student not linked in system. Attendance logged in Python only.")
                        .build();
            }

            boolean alreadyMarked = attendanceRepository
                    .findByAttendanceSessionAndStudent(session, student).isPresent();
            if (alreadyMarked) {
                return FaceAttendanceResult.builder()
                        .success(false).code("DUPLICATE")
                        .studentId(rollNumber).studentName(student.getFullName())
                        .dbStudentId(student.getId())
                        .message(student.getFullName() + " is already marked PRESENT in this session.").build();
            }

            Attendance attendance = Attendance.builder()
                    .attendanceSession(session)
                    .student(student)
                    .status(Attendance.AttendanceStatus.PRESENT)
                    .method(Attendance.MarkingMethod.FACE_RECOGNITION)
                    .confidenceScore(pyResp.get("confidence") instanceof Number n ? n.doubleValue() : null)
                    .markedAt(LocalDateTime.now())
                    .build();
            attendanceRepository.save(attendance);

            session.setPresentCount(session.getPresentCount() + 1);
            attendanceSessionRepository.save(session);

            log.info("Face attendance marked: roll={} session={}", rollNumber, session.getId());

            return FaceAttendanceResult.builder()
                    .success(true).code("MARKED")
                    .studentId(rollNumber).studentName(student.getFullName())
                    .dbStudentId(student.getId())
                    .status(Attendance.AttendanceStatus.PRESENT)
                    .markedAt(LocalDateTime.now())
                    .message(student.getFullName() + " marked PRESENT via face recognition.")
                    .build();

        } catch (ResourceAccessException e) {
            log.error("Python face service unreachable at {}", pyUrl);
            throw new RuntimeException("Face service is not running. Please start Python on port 8000.");
        } catch (Exception e) {
            log.error("Face attendance error: {}", e.getMessage(), e);
            throw new RuntimeException("Face attendance error: " + e.getMessage());
        }
    }

    // ── Multi-face concurrent attendance ──────────────────────────────────────

    @Override
    @Transactional
    public List<FaceAttendanceResult> faceMarkAttendanceMulti(FaceAttendanceRequest request, User caller) {

        AttendanceSession session = attendanceSessionRepository.findById(request.getSessionId())
                .orElseThrow(() -> new ResourceNotFoundException("AttendanceSession", request.getSessionId()));

        if (session.getStatus() != AttendanceSession.SessionStatus.ACTIVE) {
            return List.of(FaceAttendanceResult.builder()
                    .success(false).code("SESSION_CLOSED")
                    .message("This attendance session is already closed.").build());
        }

        // Build Python request body
        String subjectName = session.getSubject() != null ? session.getSubject().getName() : "Unknown";
        String teacherId   = session.getTeacher() != null
                ? (session.getTeacher().getEmployeeId() != null
                    ? session.getTeacher().getEmployeeId()
                    : String.valueOf(session.getTeacher().getId()))
                : "teacher-" + session.getId();

        Map<String, Object> pyBody = new HashMap<>();
        pyBody.put("subject",      subjectName);
        pyBody.put("session",      "session-" + session.getId());
        pyBody.put("teacher_id",   teacherId);
        if (request.getImageBase64() != null && !request.getImageBase64().isBlank()) {
            pyBody.put("image_base64", request.getImageBase64());
        }

        String pyUrl = faceServiceUrl + "/attendance/multi";

        try {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> pyResponses =
                restTemplate.postForObject(pyUrl, pyBody, List.class);

            if (pyResponses == null || pyResponses.isEmpty()) {
                return List.of(FaceAttendanceResult.builder()
                        .success(false).code("NO_FACE")
                        .message("No faces detected in the frame.").build());
            }

            List<FaceAttendanceResult> results = new ArrayList<>();
            int newlyMarked = 0;

            for (Map<String, Object> pyResp : pyResponses) {
                String code = (String) pyResp.getOrDefault("code", "UNKNOWN");

                if (!"MARKED".equals(code)) {
                    results.add(FaceAttendanceResult.builder()
                            .success("DUPLICATE".equals(code))
                            .code(code)
                            .studentId((String) pyResp.get("student_id"))
                            .studentName((String) pyResp.getOrDefault("student_name", ""))
                            .message((String) pyResp.getOrDefault("message", code))
                            .build());
                    continue;
                }

                String rollNumber  = (String) pyResp.get("student_id");
                String studentName = (String) pyResp.getOrDefault("student_name", "");

                Student student = studentRepository.findByRollNumber(rollNumber).orElse(null);
                if (student == null) {
                    log.warn("Multi-face: recognised roll={} not in Spring Boot DB", rollNumber);
                    results.add(FaceAttendanceResult.builder()
                            .success(true).code("MARKED_PYTHON_ONLY")
                            .studentId(rollNumber).studentName(studentName)
                            .message("Face recognised but not linked. Attendance in Python only.").build());
                    continue;
                }

                // Skip if already marked in THIS session
                if (attendanceRepository.findByAttendanceSessionAndStudent(session, student).isPresent()) {
                    results.add(FaceAttendanceResult.builder()
                            .success(false).code("DUPLICATE")
                            .studentId(rollNumber).studentName(student.getFullName())
                            .dbStudentId(student.getId())
                            .message(student.getFullName() + " already marked in this session.").build());
                    continue;
                }

                // Save attendance record
                Attendance attendance = Attendance.builder()
                        .attendanceSession(session)
                        .student(student)
                        .status(Attendance.AttendanceStatus.PRESENT)
                        .method(Attendance.MarkingMethod.FACE_RECOGNITION)
                        .confidenceScore(pyResp.get("confidence") instanceof Number n ? n.doubleValue() : null)
                        .markedAt(LocalDateTime.now())
                        .build();
                attendanceRepository.save(attendance);
                newlyMarked++;

                log.info("Multi-face attendance marked: roll={} session={}", rollNumber, session.getId());

                results.add(FaceAttendanceResult.builder()
                        .success(true).code("MARKED")
                        .studentId(rollNumber).studentName(student.getFullName())
                        .dbStudentId(student.getId())
                        .status(Attendance.AttendanceStatus.PRESENT)
                        .markedAt(LocalDateTime.now())
                        .message(student.getFullName() + " marked PRESENT via face recognition.").build());
            }

            if (newlyMarked > 0) {
                session.setPresentCount(session.getPresentCount() + newlyMarked);
                attendanceSessionRepository.save(session);
            }

            return results;

        } catch (ResourceAccessException e) {
            log.error("Python face service unreachable at {}", pyUrl);
            throw new RuntimeException("Face service is not running. Please start Python on port 8000.");
        } catch (Exception e) {
            log.error("Multi-face attendance error: {}", e.getMessage(), e);
            throw new RuntimeException("Multi-face attendance error: " + e.getMessage());
        }
    }

    // ── Report: student-wise attendance % ────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public List<Map<String, Object>> getAttendanceReport(
            Long departmentId, Long semesterId, Long subjectId,
            LocalDate from, LocalDate to) {

        List<AttendanceSession> sessions = attendanceSessionRepository.findBySessionDateBetween(from, to);

        if (subjectId != null) {
            sessions = sessions.stream()
                    .filter(s -> s.getSubject().getId().equals(subjectId))
                    .toList();
        }

        List<Attendance> allRecords = sessions.stream()
                .flatMap(s -> attendanceRepository.findByAttendanceSession(s).stream())
                .toList();

        Map<Long, List<Attendance>> byStudent = allRecords.stream()
                .filter(a -> {
                    Student st = a.getStudent();
                    if (departmentId != null && (st.getDepartment() == null
                            || !st.getDepartment().getId().equals(departmentId))) return false;
                    if (semesterId   != null && (st.getSemester()   == null
                            || !st.getSemester().getId().equals(semesterId)))   return false;
                    return true;
                })
                .collect(Collectors.groupingBy(a -> a.getStudent().getId()));

        return byStudent.entrySet().stream().map(e -> {
            List<Attendance> recs = e.getValue();
            Student st = recs.get(0).getStudent();
            long present = recs.stream().filter(a -> a.getStatus() == Attendance.AttendanceStatus.PRESENT).count();
            long total   = recs.size();
            double pct   = total > 0 ? (present * 100.0 / total) : 0.0;

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("studentId",      st.getId());
            row.put("rollNumber",     st.getRollNumber());
            row.put("studentName",    st.getFullName());
            row.put("department",     st.getDepartment() != null ? st.getDepartment().getName() : "");
            row.put("semester",       st.getSemester()   != null ? st.getSemester().getName()   : "");
            row.put("totalClasses",   total);
            row.put("presentClasses", present);
            row.put("absentClasses",  total - present);
            row.put("percentage",     Math.round(pct * 10.0) / 10.0);
            return row;
        }).sorted(Comparator.comparing(r -> r.get("rollNumber").toString()))
          .collect(Collectors.toList());
    }

    // ── Sessions in range ─────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public List<AttendanceSessionResponse> getSessionsInRange(Long departmentId, LocalDate from, LocalDate to) {
        List<AttendanceSession> sessions = attendanceSessionRepository.findBySessionDateBetween(from, to);
        if (departmentId != null) {
            sessions = sessions.stream()
                    .filter(s -> s.getTeacher().getDepartment() != null
                              && s.getTeacher().getDepartment().getId().equals(departmentId))
                    .toList();
        }
        return sessions.stream().map(this::toSessionResponse).toList();
    }

    // ── Mappers ───────────────────────────────────────────────────────────────

    private AttendanceResponse toAttendanceResponse(Attendance attendance) {
        return AttendanceResponse.builder()
                .id(attendance.getId())
                .sessionId(attendance.getAttendanceSession().getId())
                .studentId(attendance.getStudent().getId())
                .studentName(attendance.getStudent().getFullName())
                .rollNumber(attendance.getStudent().getRollNumber())
                .status(attendance.getStatus())
                .method(attendance.getMethod())
                .confidenceScore(attendance.getConfidenceScore())
                .markedAt(attendance.getMarkedAt())
                .build();
    }

    private AttendanceSessionResponse toSessionResponse(AttendanceSession session) {
        int markedCount = attendanceRepository.findByAttendanceSession(session).size();
        int totalStudents = (session.getTotalStudents() != null && session.getTotalStudents() > 0)
                ? session.getTotalStudents()
                : markedCount;
        int present = (int) attendanceRepository.findByAttendanceSession(session)
                .stream()
                .filter(a -> a.getStatus() == Attendance.AttendanceStatus.PRESENT)
                .count();
        return AttendanceSessionResponse.builder()
                .id(session.getId())
                .classroomId(session.getClassroom().getId())
                .roomNumber(session.getClassroom().getRoomNumber())
                .subjectId(session.getSubject().getId())
                .subjectName(session.getSubject().getName())
                .teacherId(session.getTeacher().getId())
                .teacherName(session.getTeacher().getFullName())
                .sessionDate(session.getSessionDate())
                .startTime(session.getStartTime())
                .endTime(session.getEndTime())
                .status(session.getStatus())
                .totalStudents(totalStudents)
                .presentCount(present)
                .createdAt(session.getCreatedAt())
                .build();
    }

    // ── Voice-Triggered Face Attendance Implementation ─────────────────────────

    @Override
    @Transactional
    public Map<String, Object> startFaceAttendanceSession(AttendanceSessionRequest request) {
        Map<String, Object> result = new LinkedHashMap<>();

        // 1. Check if the Python AI agent is connected before proceeding
        if (!faceAgentSessionManager.isAgentConnected()) {
            log.warn("[ATTENDANCE] Cannot start face attendance: Python agent is not connected via /ws/agent");
            result.put("status",  "AGENT_OFFLINE");
            result.put("message", "Classroom laptop camera service is offline. Please start the Python agent.");
            return result;
        }

        // 2. Create the attendance session in the database using existing logic
        AttendanceSessionResponse sessionResp;
        try {
            sessionResp = createSession(request);
        } catch (Exception e) {
            log.error("[ATTENDANCE] Failed to create attendance session: {}", e.getMessage(), e);
            result.put("status",  "ERROR");
            result.put("message", "Failed to create attendance session: " + e.getMessage());
            return result;
        }

        Long sessionId = sessionResp.getId();
        String agentJobId = UUID.randomUUID().toString();

        // 3. Retrieve registered students and their face details for this class
        List<Map<String, Object>> enrolledStudents = new ArrayList<>();
        AttendanceSession session = attendanceSessionRepository.findById(sessionId).orElse(null);
        if (session != null) {
            // Find students for this session
            List<Student> classStudents = new ArrayList<>();
            if (session.getTimetable() != null && session.getTimetable().getSection() != null
                    && session.getTimetable().getSemester() != null
                    && session.getTeacher() != null && session.getTeacher().getDepartment() != null) {
                classStudents = studentRepository.findByDepartment_IdAndSemester_IdAndSection_IdAndActiveTrue(
                        session.getTeacher().getDepartment().getId(),
                        session.getTimetable().getSemester().getId(),
                        session.getTimetable().getSection().getId()
                );
            } else if (session.getTeacher() != null && session.getTeacher().getDepartment() != null) {
                classStudents = studentRepository.findByDepartment(session.getTeacher().getDepartment());
            }

            if (classStudents.isEmpty()) {
                classStudents = studentRepository.findByActiveTrue();
            }

            for (Student s : classStudents) {
                List<StudentFace> faces = studentFaceRepository.findByStudentAndActiveTrue(s);
                Map<String, Object> sMap = new LinkedHashMap<>();
                sMap.put("studentId", s.getId());
                sMap.put("rollNumber", s.getRollNumber());
                sMap.put("fullName", s.getFullName());
                if (!faces.isEmpty()) {
                    sMap.put("encodingPath", faces.get(0).getEncodingPath());
                    sMap.put("imagePath", faces.get(0).getImagePath());
                }
                enrolledStudents.add(sMap);
            }
        }

        // 4. Prepare WebSocket payload for the Python agent
        Map<String, Object> jobPayload = new LinkedHashMap<>();
        jobPayload.put("type",             "ATTENDANCE_START");
        jobPayload.put("jobId",            agentJobId);
        jobPayload.put("sessionId",        sessionId);
        jobPayload.put("classroomId",      request.getClassroomId());
        jobPayload.put("subjectId",        sessionResp.getSubjectId());
        jobPayload.put("teacherId",        sessionResp.getTeacherId());
        jobPayload.put("subjectName",      sessionResp.getSubjectName());
        jobPayload.put("roomNumber",       sessionResp.getRoomNumber());
        jobPayload.put("totalStudents",    sessionResp.getTotalStudents());
        jobPayload.put("enrolledStudents", enrolledStudents);
        if (request.getCameraIndex() != null) {
            jobPayload.put("cameraIndex", request.getCameraIndex());
        }

        try {
            faceAgentSessionManager.sendCommand(jobPayload);
            log.info("[ATTENDANCE] Dispatched ATTENDANCE_START to agent: sessionId={}, agentJobId={}, studentsCount={}",
                    sessionId, agentJobId, enrolledStudents.size());

            result.put("status",        "STARTED");
            result.put("sessionId",     sessionId);
            result.put("agentJobId",    agentJobId);
            result.put("subjectName",   sessionResp.getSubjectName());
            result.put("roomNumber",    sessionResp.getRoomNumber());
            result.put("totalStudents", sessionResp.getTotalStudents());
            if (session != null && session.getTimetable() != null) {
                if (session.getTimetable().getSemester() != null) {
                    result.put("semesterName", session.getTimetable().getSemester().getName());
                }
                if (session.getTimetable().getSection() != null) {
                    result.put("sectionName", session.getTimetable().getSection().getName());
                }
            }
            result.put("message",       "Attendance session #" + sessionId + " started. Camera opening on laptop.");
        } catch (Exception e) {
            log.error("[ATTENDANCE] Error dispatching ATTENDANCE_START to agent: {}", e.getMessage(), e);
            result.put("status",    "ERROR");
            result.put("sessionId", sessionId);
            result.put("message",   "Session created, but failed to signal camera: " + e.getMessage());
        }

        return result;
    }

    @Override
    @Transactional
    public Map<String, Object> controlFaceAttendanceSession(Long sessionId, String agentJobId, String action) {
        Map<String, Object> result = new LinkedHashMap<>();

        if (action == null || (!action.equals("PAUSE") && !action.equals("RESUME") && !action.equals("STOP"))) {
            result.put("status",    "ERROR");
            result.put("message",   "Invalid action. Use PAUSE, RESUME, or STOP.");
            result.put("sessionId", sessionId);
            result.put("action",    action);
            return result;
        }

        AttendanceSession session = attendanceSessionRepository.findById(sessionId).orElse(null);
        if (session == null) {
            result.put("status",    "ERROR");
            result.put("message",   "Attendance session #" + sessionId + " not found.");
            result.put("sessionId", sessionId);
            result.put("action",    action);
            return result;
        }

        if ("STOP".equals(action) && session.getStatus() == AttendanceSession.SessionStatus.COMPLETED) {
            result.put("status",        "OK");
            result.put("message",       "Session is already completed.");
            result.put("sessionId",     sessionId);
            result.put("action",        action);
            result.put("presentCount",  session.getPresentCount());
            result.put("totalStudents", attendanceRepository.findByAttendanceSession(session).size());
            return result;
        }

        boolean agentCommandSent = false;
        if (faceAgentSessionManager.isAgentConnected()) {
            try {
                Map<String, Object> cmd = new LinkedHashMap<>();
                cmd.put("type",      "ATTENDANCE_" + action);
                cmd.put("sessionId", sessionId);
                if (agentJobId != null && !agentJobId.isBlank()) {
                    cmd.put("jobId", agentJobId);
                }
                faceAgentSessionManager.sendCommand(cmd);
                agentCommandSent = true;
                log.info("[ATTENDANCE] Control command ATTENDANCE_{} sent to agent for session={}", action, sessionId);
            } catch (Exception e) {
                log.warn("[ATTENDANCE] Failed to send {} to agent: {}", action, e.getMessage());
            }
        } else {
            log.warn("[ATTENDANCE] Agent offline — control command {} not delivered for session={}", action, sessionId);
        }

        if ("STOP".equals(action)) {
            try {
                AttendanceSessionResponse closed = closeSession(sessionId);
                int totalStudents = closed.getTotalStudents() != null ? closed.getTotalStudents() : 0;
                int presentCount  = closed.getPresentCount()  != null ? closed.getPresentCount()  : 0;

                result.put("status",        "OK");
                result.put("message",       agentCommandSent
                        ? "Attendance completed. Camera stopped."
                        : "Attendance session closed (agent was offline).");
                result.put("sessionId",     sessionId);
                result.put("action",        action);
                result.put("presentCount",  presentCount);
                result.put("totalStudents", totalStudents);
                log.info("[ATTENDANCE] Session {} closed: present={} total={}", sessionId, presentCount, totalStudents);
            } catch (Exception e) {
                result.put("status",    "ERROR");
                result.put("message",   "Failed to close session: " + e.getMessage());
                result.put("sessionId", sessionId);
                result.put("action",    action);
            }
        } else {
            result.put("status",    "OK");
            result.put("message",   "Command " + action + " sent to agent.");
            result.put("sessionId", sessionId);
            result.put("action",    action);
        }

        return result;
    }

    @Override
    @Transactional
    public FaceAttendanceResult markRecognizedStudent(Long sessionId, String rollNumber, Double confidence) {
        return markRecognizedStudent(sessionId, null, rollNumber, confidence);
    }

    @Override
    @Transactional
    public FaceAttendanceResult markRecognizedStudent(Long sessionId, Long studentDbId, String rollNumber, Double confidence) {
        AttendanceSession session = attendanceSessionRepository.findById(sessionId).orElse(null);
        if (session == null) {
            return FaceAttendanceResult.builder()
                    .success(false).code("SESSION_NOT_FOUND")
                    .message("Attendance session #" + sessionId + " not found.")
                    .build();
        }

        if (session.getStatus() != AttendanceSession.SessionStatus.ACTIVE) {
            return FaceAttendanceResult.builder()
                    .success(false).code("SESSION_CLOSED")
                    .message("Session #" + sessionId + " is not active (status: " + session.getStatus() + ").")
                    .build();
        }

        Student student = null;
        if (studentDbId != null) {
            student = studentRepository.findById(studentDbId).orElse(null);
        }
        if (student == null && rollNumber != null && !rollNumber.isBlank()) {
            student = studentRepository.findByRollNumber(rollNumber).orElse(null);
        }

        if (student == null) {
            log.warn("[ATTENDANCE] markRecognizedStudent: student not found: id={} roll={}", studentDbId, rollNumber);
            return FaceAttendanceResult.builder()
                    .success(false).code("STUDENT_NOT_FOUND")
                    .studentId(rollNumber != null ? rollNumber : String.valueOf(studentDbId))
                    .message("Student not found in database.")
                    .build();
        }

        // Check if student already has a record for this session (e.g. pre-initialized ABSENT)
        Optional<Attendance> existingOpt = attendanceRepository
                .findByAttendanceSessionAndStudent(session, student);

        if (existingOpt.isPresent()) {
            Attendance existing = existingOpt.get();
            if (existing.getStatus() == Attendance.AttendanceStatus.PRESENT) {
                return FaceAttendanceResult.builder()
                        .success(false).code("DUPLICATE")
                        .studentId(student.getRollNumber())
                        .studentName(student.getFullName())
                        .dbStudentId(student.getId())
                        .message(student.getFullName() + " is already marked PRESENT in this session.")
                        .build();
            }

            // Upgrade existing ABSENT record to PRESENT
            existing.setStatus(Attendance.AttendanceStatus.PRESENT);
            existing.setMethod(Attendance.MarkingMethod.FACE_RECOGNITION);
            existing.setConfidenceScore(confidence);
            existing.setMarkedAt(LocalDateTime.now());
            attendanceRepository.save(existing);

            session.setPresentCount(session.getPresentCount() + 1);
            attendanceSessionRepository.save(session);

            log.info("[ATTENDANCE] markRecognizedStudent: upgraded ABSENT -> PRESENT id={} roll={} name='{}' session={}",
                    student.getId(), student.getRollNumber(), student.getFullName(), sessionId);

            return FaceAttendanceResult.builder()
                    .success(true).code("MARKED")
                    .studentId(student.getRollNumber())
                    .studentName(student.getFullName())
                    .dbStudentId(student.getId())
                    .status(Attendance.AttendanceStatus.PRESENT)
                    .markedAt(LocalDateTime.now())
                    .message(student.getFullName() + " marked PRESENT via face recognition.")
                    .build();
        }

        // Save attendance record using existing entity
        Attendance attendance = Attendance.builder()
                .attendanceSession(session)
                .student(student)
                .status(Attendance.AttendanceStatus.PRESENT)
                .method(Attendance.MarkingMethod.FACE_RECOGNITION)
                .confidenceScore(confidence)
                .markedAt(LocalDateTime.now())
                .build();
        attendanceRepository.save(attendance);

        // Update session present count
        session.setPresentCount(session.getPresentCount() + 1);
        attendanceSessionRepository.save(session);

        log.info("[ATTENDANCE] markRecognizedStudent: PRESENT id={} roll={} name='{}' session={}",
                student.getId(), student.getRollNumber(), student.getFullName(), sessionId);

        return FaceAttendanceResult.builder()
                .success(true).code("MARKED")
                .studentId(student.getRollNumber())
                .studentName(student.getFullName())
                .dbStudentId(student.getId())
                .status(Attendance.AttendanceStatus.PRESENT)
                .markedAt(LocalDateTime.now())
                .message(student.getFullName() + " marked PRESENT via face recognition.")
                .build();
    }
}
