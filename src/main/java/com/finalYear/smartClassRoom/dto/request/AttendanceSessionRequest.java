package com.finalYear.smartClassRoom.dto.request;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalTime;

@Data
public class AttendanceSessionRequest {

    private Long timetableId;

    @NotNull(message = "Classroom is required")
    private Long classroomId;

    private Long subjectId;

    private Long teacherId;

    private LocalDate sessionDate;

    private LocalTime startTime;
}