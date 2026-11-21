package com.finalYear.smartClassRoom.service;

import com.finalYear.smartClassRoom.dto.response.FaceRegistrationResponse;
import com.finalYear.smartClassRoom.dto.response.StudentResponse;
import com.finalYear.smartClassRoom.entity.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface FaceRegistrationService {
    FaceRegistrationResponse registerFace(Long studentId, User caller);
    FaceRegistrationResponse getFaceStatus(Long studentId);
    Page<StudentResponse> getPendingFaceStudents(Long departmentId, Pageable pageable);
    java.util.Map<String, Object> getAgentStatus();
}
