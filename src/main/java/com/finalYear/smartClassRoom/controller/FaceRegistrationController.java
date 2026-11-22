package com.finalYear.smartClassRoom.controller;

import com.finalYear.smartClassRoom.dto.response.FaceRegistrationResponse;
import com.finalYear.smartClassRoom.entity.Student;
import com.finalYear.smartClassRoom.entity.User;
import com.finalYear.smartClassRoom.exception.ResourceNotFoundException;
import com.finalYear.smartClassRoom.repository.StudentFaceRepository;
import com.finalYear.smartClassRoom.repository.StudentRepository;
import com.finalYear.smartClassRoom.service.CurrentUserContextService;
import com.finalYear.smartClassRoom.service.FaceRegistrationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/faces")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
@Slf4j
public class FaceRegistrationController {

    private final FaceRegistrationService faceRegistrationService;
    private final CurrentUserContextService currentUserContextService;
    private final StudentRepository studentRepository;
    private final StudentFaceRepository studentFaceRepository;

    @PostMapping("/register/{studentId}")
    @PreAuthorize("hasAnyRole('TEACHER','HOD','ADMIN','SUPER_ADMIN')")
    public ResponseEntity<FaceRegistrationResponse> registerFace(@PathVariable Long studentId) {
        User caller = currentUserContextService.getCaller();
        FaceRegistrationResponse result = faceRegistrationService.registerFace(studentId, caller);
        if (!result.isSuccess()) {
            // Return 400 so the frontend axios catch block receives the response body
            return ResponseEntity.badRequest().body(result);
        }
        return ResponseEntity.ok(result);
    }

    @GetMapping("/status/{studentId}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<FaceRegistrationResponse> getFaceStatus(@PathVariable Long studentId) {
        return ResponseEntity.ok(faceRegistrationService.getFaceStatus(studentId));
    }

    @GetMapping("/agent-status")
    public ResponseEntity<Map<String, Object>> getAgentStatus() {
        return ResponseEntity.ok(faceRegistrationService.getAgentStatus());
    }

    /**
     * Reset a student's face registration status back to ACCOUNT_CREATED.
     * Removes all face records and clears encoding path.
     * Only ADMIN / SUPER_ADMIN may call this.
     */
    @DeleteMapping("/reset/{studentId}")
    @PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN')")
    public ResponseEntity<Map<String, Object>> resetFaceRegistration(@PathVariable Long studentId) {
        Student student = studentRepository.findById(studentId)
                .orElseThrow(() -> new ResourceNotFoundException("Student", studentId));

        // Delete all face records
        if (studentFaceRepository.existsByStudent(student)) {
            studentFaceRepository.deleteByStudent(student);
            log.info("Deleted all face records for studentId={} (reset)", studentId);
        }

        // Revert lifecycle status
        student.setRegistrationStatus(Student.RegistrationStatus.ACCOUNT_CREATED);
        studentRepository.save(student);

        log.info("Face registration reset for studentId={} rollNumber={}", studentId, student.getRollNumber());

        return ResponseEntity.ok(Map.of(
                "studentId",    studentId,
                "rollNumber",   student.getRollNumber(),
                "studentName",  student.getFullName(),
                "status",       "ACCOUNT_CREATED",
                "message",      "Face registration has been reset. Student can be re-enrolled."
        ));
    }
}
