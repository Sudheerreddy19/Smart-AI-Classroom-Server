package com.finalYear.smartClassRoom.controller;

import com.finalYear.smartClassRoom.dto.response.FaceRegistrationResponse;
import com.finalYear.smartClassRoom.entity.User;
import com.finalYear.smartClassRoom.service.CurrentUserContextService;
import com.finalYear.smartClassRoom.service.FaceRegistrationService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/faces")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class FaceRegistrationController {

    private final FaceRegistrationService faceRegistrationService;
    private final CurrentUserContextService currentUserContextService;

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
}
