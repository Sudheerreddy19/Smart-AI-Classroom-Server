package com.finalYear.smartClassRoom.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EnvironmentResponse {

    private Long id;

    private Long classroomId;

    private String roomNumber;

    private Double temperature;

    private Double humidity;

    private Double co2Level;

    private Double lightLevel;

    private Double noiseLevel;

    private Double airQualityIndex;

    private String roomState;       // "ACTIVE" or "EMPTY" (raw PIR value from ESP32)
    private Boolean isDark;          // true / false
    private Boolean gasLeak;         // true / false
    private Double gasPpm;           // Numeric gas reading
    private String fanStatus;        // "ON" or "OFF"
    private String lightStatus;      // "ON" or "OFF"
    private Integer ldrRaw;          // Raw LDR ADC value
    private Boolean motionDetected;  // PIR motion sensor
    private String deviceId;         // Hardware device ID e.g. "ESP32_01"
    private String hardwareClassroomId; // Hardware classroom string e.g. "CLASSROOM_01"

    // ── Occupancy & Dashboard fields (populated by automation engine) ─────────
    private String occupancyStatus;          // "OCCUPIED", "COUNTDOWN", or "VACANT"
    private Long vacancyCountdownSeconds;    // seconds remaining before vacancy declared (null if not in countdown)
    private String decisionSummary;          // Human-readable latest automation decision message

    private LocalDateTime recordedAt;
}
