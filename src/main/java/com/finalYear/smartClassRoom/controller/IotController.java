package com.finalYear.smartClassRoom.controller;

import com.finalYear.smartClassRoom.entity.Classroom;
import com.finalYear.smartClassRoom.entity.EnvironmentData;
import com.finalYear.smartClassRoom.repository.ClassroomRepository;
import com.finalYear.smartClassRoom.repository.EnvironmentDataRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Dedicated IoT endpoint for hardware devices (ESP32).
 *
 * Hardware sends JSON in this exact nested structure:
 * {
 *   "classroomId": "CLASSROOM_01",
 *   "deviceId":    "ESP32_01",
 *   "roomState":   "ACTIVE" | "IDLE",
 *   "sensors": {
 *     "temperature":   32.4,
 *     "humidity":      75.4,
 *     "ldrRaw":        1234,
 *     "lightIntensity": 85.0,
 *     "isDark":        true,
 *     "gasLeak":       false,
 *     "motionDetected": true
 *   },
 *   "actuators": {
 *     "fanStatus":   "ON",
 *     "lightStatus": "ON"
 *   }
 * }
 *
 * Classroom lookup priority:
 *   1. Match classroomId string against Classroom.esp32Id column
 *   2. Match classroomId string against Classroom.roomNumber column
 *   3. Fallback to the first active classroom in the database
 */
@Slf4j
@RestController
@RequestMapping("/api/iot")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class IotController {

    private final EnvironmentDataRepository environmentDataRepository;
    private final ClassroomRepository classroomRepository;

    // ── Nested DTOs matching exact hardware JSON format ───────────────────────

    public record SensorsPayload(
            Double temperature,
            Double humidity,
            Integer ldrRaw,
            Double lightIntensity,
            Boolean isDark,
            Boolean gasLeak,
            Boolean motionDetected
    ) {}

    public record ActuatorsPayload(
            String fanStatus,
            String lightStatus
    ) {}

    public record HardwareTelemetryRequest(
            String classroomId,     // String ID from hardware e.g. "CLASSROOM_01"
            String deviceId,        // Hardware device ID e.g. "ESP32_01"
            String roomState,       // "ACTIVE" or "IDLE"
            SensorsPayload sensors,
            ActuatorsPayload actuators
    ) {}

    // ── Main telemetry ingestion endpoint (no auth required for hardware) ─────

    @PostMapping("/telemetry")
    public ResponseEntity<String> receiveTelemetry(@RequestBody HardwareTelemetryRequest req) {

        log.info("IoT Telemetry received | classroomId={} deviceId={} roomState={} temp={} humidity={} fan={} light={}",
                req.classroomId(), req.deviceId(), req.roomState(),
                req.sensors() != null ? req.sensors().temperature() : "null",
                req.sensors() != null ? req.sensors().humidity() : "null",
                req.actuators() != null ? req.actuators().fanStatus() : "null",
                req.actuators() != null ? req.actuators().lightStatus() : "null");

        // ── 1. Find classroom: try esp32Id, then roomNumber, then first active ──
        Classroom classroom = null;

        if (req.classroomId() != null && !req.classroomId().isBlank()) {
            // Try matching classroomId string against the esp32_id column
            classroom = classroomRepository.findByEsp32Id(req.classroomId()).orElse(null);

            // Try matching against room_number column
            if (classroom == null) {
                classroom = classroomRepository.findByRoomNumber(req.classroomId()).orElse(null);
            }
        }

        // Fallback: use any active classroom
        if (classroom == null) {
            List<Classroom> active = classroomRepository.findByActiveTrue();
            if (!active.isEmpty()) {
                classroom = active.get(0);
                log.warn("No classroom matched '{}'. Falling back to first active classroom: {}",
                        req.classroomId(), classroom.getRoomNumber());
            }
        }

        if (classroom == null) {
            log.error("No active classroom found for classroomId='{}'", req.classroomId());
            return ResponseEntity.badRequest()
                    .body("No active classroom found. Please create a classroom first.");
        }

        // ── 2. Extract sensor and actuator values ──────────────────────────────
        SensorsPayload s = req.sensors();
        ActuatorsPayload a = req.actuators();

        Double temperature    = s != null ? s.temperature()    : null;
        Double humidity       = s != null ? s.humidity()       : null;
        Integer ldrRaw        = s != null ? s.ldrRaw()         : null;
        Double lightIntensity = s != null ? s.lightIntensity() : null;
        Boolean isDark        = s != null ? s.isDark()         : null;
        Boolean gasLeak       = s != null ? s.gasLeak()        : null;
        Boolean motionDetected = s != null ? s.motionDetected() : null;
        String fanStatus      = a != null ? a.fanStatus()      : null;
        String lightStatus    = a != null ? a.lightStatus()    : null;

        // ── 3. Build and save EnvironmentData ──────────────────────────────────
        EnvironmentData data = EnvironmentData.builder()
                .classroom(classroom)
                .temperature(temperature)
                .humidity(humidity)
                .lightLevel(lightIntensity)       // store lightIntensity% in lightLevel column
                .ldrRaw(ldrRaw)
                .isDark(isDark)
                .gasLeak(gasLeak)
                .motionDetected(motionDetected)
                .roomState(req.roomState())
                .fanStatus(fanStatus)
                .lightStatus(lightStatus)
                .deviceId(req.deviceId())
                .hardwareClassroomId(req.classroomId())
                .build();

        environmentDataRepository.save(data);

        log.info("Telemetry saved successfully for classroom '{}' (DB id={})",
                classroom.getRoomNumber(), classroom.getId());

        return ResponseEntity.ok("Telemetry received and stored successfully.");
    }
}
