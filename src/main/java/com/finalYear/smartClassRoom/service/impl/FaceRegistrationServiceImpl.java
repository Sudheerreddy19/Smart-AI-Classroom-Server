package com.finalYear.smartClassRoom.service.impl;

import com.finalYear.smartClassRoom.dto.response.FaceRegistrationResponse;
import com.finalYear.smartClassRoom.dto.response.StudentResponse;
import com.finalYear.smartClassRoom.entity.Student;
import com.finalYear.smartClassRoom.entity.StudentFace;
import com.finalYear.smartClassRoom.entity.Teacher;
import com.finalYear.smartClassRoom.entity.User;
import com.finalYear.smartClassRoom.exception.ResourceNotFoundException;
import com.finalYear.smartClassRoom.repository.StudentFaceRepository;
import com.finalYear.smartClassRoom.repository.StudentRepository;
import com.finalYear.smartClassRoom.repository.TeacherRepository;
import com.finalYear.smartClassRoom.service.FaceRegistrationService;
import com.finalYear.smartClassRoom.websocket.FaceAgentSessionManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Service
@RequiredArgsConstructor
@Slf4j
public class FaceRegistrationServiceImpl implements FaceRegistrationService {

    private final StudentRepository        studentRepository;
    private final TeacherRepository        teacherRepository;
    private final StudentFaceRepository    studentFaceRepository;
    private final RestTemplate             restTemplate;
    private final FaceAgentSessionManager  faceAgentSessionManager;

    @Value("${face.service.url:http://localhost:8000}")
    private String faceServiceUrl;

    @Value("${classai.capture.job-timeout-seconds:90}")
    private long captureJobTimeoutSeconds;

    // ─────────────────────────────────────────────────────────────────────────
    // Register face — calls Python service and updates student status
    // ─────────────────────────────────────────────────────────────────────────

    @Override
    @Transactional
    public FaceRegistrationResponse registerFace(Long studentId, User caller) {
        return registerFace(studentId, null, caller);
    }

    @Override
    @Transactional
    public FaceRegistrationResponse registerFace(Long studentId, Integer cameraIndex, User caller) {
        Student student = studentRepository.findById(studentId)
                .orElseThrow(() -> new ResourceNotFoundException("Student", studentId));

        // Allow re-capture even if already FACE_REGISTERED (teacher re-captures)
        if (student.getRegistrationStatus() == Student.RegistrationStatus.NOT_REGISTERED) {
            throw new IllegalStateException(
                    "Student must create their account first before face registration. " +
                    "Current status: NOT_REGISTERED");
        }

        // Resolve teacher profile if caller is TEACHER/HOD
        Teacher capturedByTeacher = null;
        if (caller.getRole() == User.Role.TEACHER || caller.getRole() == User.Role.HOD) {
            capturedByTeacher = teacherRepository.findByUserId(caller.getId()).orElse(null);
        }

        final Teacher finalTeacher = capturedByTeacher;

        // Prepare job payload
        String jobId = UUID.randomUUID().toString();
        Map<String, Object> jobPayload = new HashMap<>();
        jobPayload.put("type",           "CAPTURE_JOB");
        jobPayload.put("jobId",          jobId);
        jobPayload.put("studentDbId",    studentId);
        jobPayload.put("registerNumber", student.getRollNumber());
        jobPayload.put("student_id",     String.valueOf(studentId));
        jobPayload.put("studentName",    student.getFullName());
        jobPayload.put("department",     student.getDepartment() != null ? student.getDepartment().getName() : "");
        jobPayload.put("semester",       student.getSemester()   != null ? String.valueOf(student.getSemester().getNumber()) : "");
        jobPayload.put("section",        student.getSection()    != null ? student.getSection().getName() : "");
        jobPayload.put("teacher_id",     finalTeacher != null ? String.valueOf(finalTeacher.getId()) : null);
        jobPayload.put("teacherId",      finalTeacher != null ? finalTeacher.getId() : null);
        if (cameraIndex != null) {
            jobPayload.put("cameraIndex", cameraIndex);
        }

        Map<String, Object> response;

        try {
            if (faceAgentSessionManager.isAgentConnected()) {
                log.info("Face AI Agent is connected. Dispatching CAPTURE_JOB via WebSocket: jobId={}, studentId={}",
                        jobId, studentId);
                try {
                    CompletableFuture<Map<String, Object>> future =
                            faceAgentSessionManager.dispatchJob(jobPayload, captureJobTimeoutSeconds);
                    response = future.get(captureJobTimeoutSeconds, TimeUnit.SECONDS);
                } catch (TimeoutException e) {
                    log.error("Capture job {} timed out after {}s", jobId, captureJobTimeoutSeconds);
                    throw new RuntimeException("Face capture timed out. Please ensure the student is facing the camera and retry.");
                } catch (ExecutionException e) {
                    Throwable cause = e.getCause() != null ? e.getCause() : e;
                    log.error("Capture job {} execution failed: {}", jobId, cause.getMessage());
                    throw new RuntimeException("Face capture failed: " + cause.getMessage());
                }
            } else {
                // Fallback to direct HTTP for local development environments
                log.warn("Agent WebSocket not connected. Attempting HTTP fallback to {}", faceServiceUrl);
                String url = faceServiceUrl + "/face/capture/" + studentId;
                try {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> httpResponse = restTemplate.postForObject(url, jobPayload, Map.class);
                    response = httpResponse;
                } catch (ResourceAccessException e) {
                    log.error("Face AI service unreachable via WebSocket and HTTP ({})", url);
                    throw new RuntimeException(
                            "Face service is not running. Please start the Python service on your classroom laptop.");
                }
            }

            if (response == null || !Boolean.TRUE.equals(response.get("success"))) {
                String msg    = response != null ? (String) response.get("message")   : "No response from face service";
                String errCode = response != null ? (String) response.get("errorCode") : "CAPTURE_FAILED";

                // ── DUPLICATE_FACE: return a structured failure (don't throw) ──
                if ("DUPLICATE_FACE".equals(errCode) && response != null) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> existing = (Map<String, Object>) response.get("existingStudent");
                    String reportedRoll = existing != null ? String.valueOf(existing.getOrDefault("rollNumber", "")) : "";
                    
                    Map<String, String> existingStudentMap = new java.util.LinkedHashMap<>();
                    
                    // Validate against Spring Boot database (never blindly trust python client data)
                    if (reportedRoll != null && !reportedRoll.isBlank()) {
                        studentRepository.findByRollNumber(reportedRoll).ifPresentOrElse(s -> {
                            existingStudentMap.put("id",          String.valueOf(s.getId()));
                            existingStudentMap.put("studentId",   String.valueOf(s.getId()));
                            existingStudentMap.put("rollNumber",  s.getRollNumber());
                            existingStudentMap.put("name",        s.getFullName());
                            existingStudentMap.put("department",  s.getDepartment() != null ? s.getDepartment().getName() : "");
                            existingStudentMap.put("semester",    s.getSemester() != null ? String.valueOf(s.getSemester().getNumber()) : "");
                            existingStudentMap.put("status",      s.getRegistrationStatus() != null ? s.getRegistrationStatus().name() : "");
                        }, () -> {
                            if (existing != null) {
                                existingStudentMap.put("rollNumber",  reportedRoll);
                                existingStudentMap.put("name",        String.valueOf(existing.getOrDefault("name", "")));
                                existingStudentMap.put("department",  String.valueOf(existing.getOrDefault("department", "")));
                                existingStudentMap.put("semester",    String.valueOf(existing.getOrDefault("semester", "")));
                            }
                        });
                    } else if (existing != null) {
                        existingStudentMap.put("rollNumber",  String.valueOf(existing.getOrDefault("rollNumber", "")));
                        existingStudentMap.put("name",        String.valueOf(existing.getOrDefault("name", "")));
                        existingStudentMap.put("department",  String.valueOf(existing.getOrDefault("department", "")));
                        existingStudentMap.put("semester",    String.valueOf(existing.getOrDefault("semester", "")));
                    }

                    log.warn("DUPLICATE_FACE rejected for studentId={}: {}", studentId, msg);
                    return FaceRegistrationResponse.builder()
                            .studentId(studentId)
                            .rollNumber(student.getRollNumber())
                            .studentName(student.getFullName())
                            .success(false)
                            .errorCode(errCode)
                            .message(msg)
                            .existingStudent(existingStudentMap)
                            .build();
                }

                // ── UNCERTAIN_MATCH: return structured guidance (don't throw) ──
                if ("UNCERTAIN_MATCH".equals(errCode) && response != null) {
                    log.info("UNCERTAIN_MATCH for studentId={}: {}", studentId, msg);
                    return FaceRegistrationResponse.builder()
                            .studentId(studentId)
                            .rollNumber(student.getRollNumber())
                            .studentName(student.getFullName())
                            .success(false)
                            .errorCode("UNCERTAIN_MATCH")
                            .message(msg)
                            .build();
                }

                // ── Return structured failure response instead of throwing 500 error ──
                log.warn("Face registration failed for studentId={}: [{}] {}", studentId, errCode, msg);
                return FaceRegistrationResponse.builder()
                        .studentId(studentId)
                        .rollNumber(student.getRollNumber())
                        .studentName(student.getFullName())
                        .success(false)
                        .errorCode(errCode != null ? errCode : "CAPTURE_FAILED")
                        .message(msg)
                        .build();
            }

            // Extract response data safely (handling both camelCase and snake_case)
            Object imgCountObj = response.get("imagesCaptured");
            if (imgCountObj == null) imgCountObj = response.get("images_captured");
            int imagesCaptured = imgCountObj instanceof Number n ? n.intValue() : 0;

            String encodingPath = (String) response.get("encodingPath");
            if (encodingPath == null) encodingPath = (String) response.get("encoding_path");

            @SuppressWarnings("unchecked")
            List<String> imagePaths = (List<String>) response.getOrDefault("imagePaths",
                    response.getOrDefault("image_paths", List.of()));

            // Remove any previous face records for this student (prevent duplicates on re-capture)
            if (studentFaceRepository.existsByStudent(student)) {
                studentFaceRepository.deleteByStudent(student);
                log.info("Removed old face records for studentId={} before re-capture", studentId);
            }

            // Persist new face record
            StudentFace face = StudentFace.builder()
                    .student(student)
                    .encodingPath(encodingPath)
                    .imagePath(imagePaths.isEmpty() ? "" : imagePaths.get(0))
                    .angleType(StudentFace.AngleType.FRONT)
                    .capturedByTeacher(finalTeacher)
                    .captureDate(LocalDateTime.now())
                    .totalImages(imagesCaptured)
                    .active(true)
                    .build();

            studentFaceRepository.save(face);

            // Advance lifecycle status
            student.setRegistrationStatus(Student.RegistrationStatus.FACE_REGISTERED);
            studentRepository.save(student);

            log.info("Face registered for studentId={} by userId={}", studentId, caller.getId());

            return FaceRegistrationResponse.builder()
                    .studentId(studentId)
                    .rollNumber(student.getRollNumber())
                    .studentName(student.getFullName())
                    .success(true)
                    .imagesCaptured(imagesCaptured)
                    .encodingPath(encodingPath)
                    .message("Face registration successful! " + imagesCaptured + " images captured.")
                    .capturedAt(LocalDateTime.now())
                    .build();

        } catch (IllegalStateException e) {
            log.error("State error during face registration for studentId={}: {}", studentId, e.getMessage());
            return FaceRegistrationResponse.builder()
                    .studentId(studentId)
                    .rollNumber(student.getRollNumber())
                    .studentName(student.getFullName())
                    .success(false)
                    .errorCode("SERVICE_UNAVAILABLE")
                    .message(e.getMessage())
                    .build();
        } catch (RuntimeException e) {
            log.error("Runtime error during face registration for studentId={}: {}", studentId, e.getMessage());
            return FaceRegistrationResponse.builder()
                    .studentId(studentId)
                    .rollNumber(student.getRollNumber())
                    .studentName(student.getFullName())
                    .success(false)
                    .errorCode("CAPTURE_FAILED")
                    .message(e.getMessage())
                    .build();
        } catch (Exception e) {
            log.error("Unexpected error during face registration for studentId={}: {}", studentId, e.getMessage(), e);
            return FaceRegistrationResponse.builder()
                    .studentId(studentId)
                    .rollNumber(student.getRollNumber())
                    .studentName(student.getFullName())
                    .success(false)
                    .errorCode("INTERNAL_ERROR")
                    .message("Face capture error: " + e.getMessage())
                    .build();
        }
    }

    @Override
    public Map<String, Object> getAgentStatus() {
        Map<String, Object> status = new HashMap<>();
        status.put("connected", faceAgentSessionManager.isAgentConnected());
        status.put("agentId", faceAgentSessionManager.getConnectedAgentId());
        status.put("lastHeartbeat", faceAgentSessionManager.getLastHeartbeat());
        return status;
    }


    // ─────────────────────────────────────────────────────────────────────────
    // Get face status for a student
    // ─────────────────────────────────────────────────────────────────────────

    @Override
    public FaceRegistrationResponse getFaceStatus(Long studentId) {
        Student student = studentRepository.findById(studentId)
                .orElseThrow(() -> new ResourceNotFoundException("Student", studentId));

        boolean hasFace = student.getRegistrationStatus() == Student.RegistrationStatus.FACE_REGISTERED;
        return FaceRegistrationResponse.builder()
                .studentId(studentId)
                .rollNumber(student.getRollNumber())
                .studentName(student.getFullName())
                .success(hasFace)
                .message(hasFace ? "Face registered" : "Face not yet registered")
                .build();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // List students pending face registration
    // ─────────────────────────────────────────────────────────────────────────

    @Override
    public Page<StudentResponse> getPendingFaceStudents(Long departmentId, Pageable pageable) {
        return studentRepository.findPendingFaceRegistration(departmentId, pageable)
                .map(this::toResponse);
    }

    // ── Mapper ───────────────────────────────────────────────────────────────

    private StudentResponse toResponse(Student s) {
        return StudentResponse.builder()
                .id(s.getId())
                .userId(s.getUser() != null ? s.getUser().getId() : null)
                .email(s.getEmail())
                .rollNumber(s.getRollNumber())
                .firstName(s.getFirstName())
                .lastName(s.getLastName())
                .departmentId(s.getDepartment()  != null ? s.getDepartment().getId()   : null)
                .departmentName(s.getDepartment() != null ? s.getDepartment().getName() : null)
                .semesterId(s.getSemester()      != null ? s.getSemester().getId()     : null)
                .semesterName(s.getSemester()    != null ? s.getSemester().getName()   : null)
                .sectionId(s.getSection()        != null ? s.getSection().getId()      : null)
                .sectionName(s.getSection()      != null ? s.getSection().getName()    : null)
                .dateOfBirth(s.getDateOfBirth())
                .phone(s.getPhone())
                .admissionNumber(s.getAdmissionNumber())
                .officialEmail(s.getOfficialEmail())
                .branch(s.getBranch())
                .academicYear(s.getAcademicYear())
                .registrationStatus(s.getRegistrationStatus())
                .active(s.isActive())
                .createdAt(s.getCreatedAt())
                .build();
    }
}
