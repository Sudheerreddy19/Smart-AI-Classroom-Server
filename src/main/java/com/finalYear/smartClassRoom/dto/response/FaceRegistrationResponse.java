package com.finalYear.smartClassRoom.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.time.LocalDateTime;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FaceRegistrationResponse {
    private Long   studentId;
    private String rollNumber;
    private String studentName;
    private boolean success;
    private int    imagesCaptured;
    private String encodingPath;
    private String message;
    private String errorCode;
    private LocalDateTime capturedAt;

    /**
     * Present only when errorCode == "DUPLICATE_FACE".
     * Contains: rollNumber, name, department, semester of the existing student.
     */
    private Map<String, String> existingStudent;
}
