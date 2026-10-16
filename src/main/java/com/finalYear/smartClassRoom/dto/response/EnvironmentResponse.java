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
    
    private String roomState;       // "ACTIVE" or "EMPTY"
    private Boolean isDark;          // true / false
    private Boolean gasLeak;         // true / false
    private Double gasPpm;           // Numeric gas reading
    private String fanStatus;        // "ON" or "OFF"
    private String lightStatus;      // "ON" or "OFF"
    private Integer ldrRaw;          // Raw LDR ADC value
    private Boolean motionDetected;  // PIR motion sensor
    private String deviceId;         // Hardware device ID e.g. "ESP32_01"
    private String hardwareClassroomId; // Hardware classroom string e.g. "CLASSROOM_01"

    private LocalDateTime recordedAt;
}