package com.finalYear.smartClassRoom.controller;

import com.finalYear.smartClassRoom.ai.decision.AutomationDecisionResult;
import com.finalYear.smartClassRoom.ai.decision.ClassroomAutomationEngine;
import com.finalYear.smartClassRoom.dto.response.EnvironmentResponse;
import com.finalYear.smartClassRoom.entity.Classroom;
import com.finalYear.smartClassRoom.entity.EnvironmentData;
import com.finalYear.smartClassRoom.repository.ClassroomRepository;
import com.finalYear.smartClassRoom.repository.EnvironmentDataRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/environment")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
@Slf4j
public class EnvironmentController {

    private final EnvironmentDataRepository environmentDataRepository;
    private final ClassroomRepository classroomRepository;
    private final ClassroomAutomationEngine automationEngine;
    private final com.finalYear.smartClassRoom.service.DeviceOverrideService deviceOverrideService;

    /** Latest reading for every classroom */
    @GetMapping("/latest")
    public ResponseEntity<List<EnvironmentResponse>> getAllLatest() {
        List<Classroom> classrooms = classroomRepository.findByActiveTrue();
        List<EnvironmentResponse> results = classrooms.stream()
                .map(c -> {
                    EnvironmentData data = environmentDataRepository
                            .findTopByClassroomOrderByRecordedAtDesc(c);
                    return toResponse(c, data);
                })
                .collect(Collectors.toList());
        return ResponseEntity.ok(results);
    }

    /** Latest reading for a specific classroom */
    @GetMapping("/latest/{classroomId}")
    public ResponseEntity<EnvironmentResponse> getLatest(@PathVariable Long classroomId) {
        Classroom classroom = classroomRepository.findById(classroomId)
                .orElseThrow(() -> new com.finalYear.smartClassRoom.exception
                        .ResourceNotFoundException("Classroom", classroomId));
        EnvironmentData data = environmentDataRepository
                .findTopByClassroomOrderByRecordedAtDesc(classroom);
        return ResponseEntity.ok(toResponse(classroom, data));
    }

    /** Historical data for a classroom (last N entries) */
    @GetMapping("/history/{classroomId}")
    public ResponseEntity<List<EnvironmentResponse>> getHistory(
            @PathVariable Long classroomId,
            @RequestParam(defaultValue = "50") int limit) {
        Classroom classroom = classroomRepository.findById(classroomId)
                .orElseThrow(() -> new com.finalYear.smartClassRoom.exception
                        .ResourceNotFoundException("Classroom", classroomId));
        List<EnvironmentData> data = environmentDataRepository
                .findByClassroomOrderByRecordedAtAsc(classroom);
        int size = data.size();
        List<EnvironmentData> paged = data.subList(Math.max(0, size - limit), size);
        List<EnvironmentResponse> result = paged.stream()
                .map(d -> toResponse(classroom, d))
                .collect(Collectors.toList());
        return ResponseEntity.ok(result);
    }

    /** Dashboard summary (latest across all classrooms) */
    @GetMapping("/dashboard")
    public ResponseEntity<List<EnvironmentResponse>> getDashboard() {
        return getAllLatest();
    }

    // ── IoT data ingestion (called by ESP32 / Arduino hardware) ───────────────
    @PostMapping(value = {"/ingest", "/telemetry"})
    public ResponseEntity<TelemetryIngestResponse> ingest(@RequestBody EnvironmentIngestRequest req) {
        Classroom classroom = null;
        if (req.classroomId() != null) {
            classroom = classroomRepository.findById(req.classroomId()).orElse(null);
        }
        if (classroom == null && req.esp32Id() != null && !req.esp32Id().isBlank()) {
            classroom = classroomRepository.findByEsp32Id(req.esp32Id()).orElse(null);
        }
        if (classroom == null && req.roomNumber() != null && !req.roomNumber().isBlank()) {
            classroom = classroomRepository.findByRoomNumber(req.roomNumber()).orElse(null);
        }
        if (classroom == null) {
            List<Classroom> allActive = classroomRepository.findByActiveTrue();
            classroom = allActive.isEmpty() ? null : allActive.get(0);
        }

        if (classroom == null) {
            return ResponseEntity.badRequest().body(new TelemetryIngestResponse(
                    "ERROR: No active classroom found", null, "OFF", "OFF", false,
                    "UNKNOWN", null, "No classroom found", java.util.Collections.emptyList()));
        }

        Double light = req.lightIntensity() != null ? req.lightIntensity() : req.lightLevel();
        Boolean motion = req.motionDetected() != null
                ? req.motionDetected()
                : "ACTIVE".equalsIgnoreCase(req.roomState());
        Integer ldr = req.ldrRaw() != null
                ? req.ldrRaw()
                : (light != null ? light.intValue() : null);

        EnvironmentData data = EnvironmentData.builder()
                .classroom(classroom)
                .temperature(req.temperature())
                .humidity(req.humidity())
                .co2Level(req.co2Level())
                .lightLevel(light)
                .noiseLevel(req.noiseLevel())
                .airQualityIndex(req.airQualityIndex())
                .roomState(req.roomState())
                .isDark(req.isDark())
                .gasLeak(req.gasLeak())
                .gasPpm(req.gasPpm())
                .fanStatus(req.fanStatus())
                .lightStatus(req.lightStatus())
                .ldrRaw(ldr)
                .motionDetected(motion)
                .build();

        EnvironmentData saved = environmentDataRepository.save(data);

        // ── Autonomous Sensor-Driven Automation Evaluation ────────────────────
        List<AutomationDecisionResult> decisions = java.util.Collections.emptyList();
        try {
            if (automationEngine != null) {
                decisions = automationEngine.evaluateTelemetry(classroom, saved);
            }
        } catch (Exception e) {
            log.error("ClassroomAutomationEngine evaluation failed for classroom {}: {}",
                    classroom.getId(), e.getMessage(), e);
        }

        String activeFan   = saved.getFanStatus()   != null ? saved.getFanStatus()   : "OFF";
        String activeLight = saved.getLightStatus()  != null ? saved.getLightStatus() : "OFF";
        boolean gasEmergency = Boolean.TRUE.equals(saved.getGasLeak())
                || (saved.getGasPpm() != null && saved.getGasPpm() > 400.0);

        // Occupancy status from in-memory engine state
        Long classroomId = classroom.getId();
        String occupancyStatus = automationEngine.getOccupancyStatus(classroomId);
        Long vacancyCountdown  = automationEngine.getVacancyCountdown(classroomId);

        // Latest decision summary for ESP32 serial monitor logging
        String decisionSummary = decisions.isEmpty() ? "No decisions" :
                decisions.get(decisions.size() - 1).reason();

        return ResponseEntity.ok(new TelemetryIngestResponse(
                "SUCCESS",
                classroomId,
                activeFan,
                activeLight,
                gasEmergency,
                occupancyStatus,
                vacancyCountdown,
                decisionSummary,
                decisions
        ));
    }

    /**
     * Inspect or manually trigger decision engine evaluation for a classroom.
     */
    @PostMapping("/evaluate/{classroomId}")
    public ResponseEntity<List<AutomationDecisionResult>> evaluateNow(@PathVariable Long classroomId) {
        Classroom classroom = classroomRepository.findById(classroomId)
                .orElseThrow(() -> new com.finalYear.smartClassRoom.exception
                        .ResourceNotFoundException("Classroom", classroomId));
        EnvironmentData data = environmentDataRepository.findTopByClassroomOrderByRecordedAtDesc(classroom);
        if (data == null) {
            return ResponseEntity.ok(List.of());
        }
        return ResponseEntity.ok(automationEngine.evaluateTelemetry(classroom, data));
    }

    /**
     * Get active durable teacher manual overrides for a classroom.
     */
    @GetMapping("/overrides/{classroomId}")
    public ResponseEntity<List<com.finalYear.smartClassRoom.entity.DeviceOverride>> getActiveOverrides(@PathVariable Long classroomId) {
        Classroom classroom = classroomRepository.findById(classroomId)
                .orElseThrow(() -> new com.finalYear.smartClassRoom.exception
                        .ResourceNotFoundException("Classroom", classroomId));
        return ResponseEntity.ok(deviceOverrideService.getActiveOverrides(classroom));
    }

    /**
     * Clear / cancel a manual override (teachers/admins only).
     */
    @DeleteMapping("/overrides/{overrideId}")
    public ResponseEntity<String> clearOverride(@PathVariable Long overrideId) {
        deviceOverrideService.clearOverride(overrideId);
        return ResponseEntity.ok("Override cancelled successfully.");
    }

    /** Current occupancy status for a classroom (dashboard polling endpoint). */
    @GetMapping("/occupancy/{classroomId}")
    public ResponseEntity<OccupancyStatusResponse> getOccupancy(@PathVariable Long classroomId) {
        return ResponseEntity.ok(new OccupancyStatusResponse(
                classroomId,
                automationEngine.getOccupancyStatus(classroomId),
                automationEngine.getVacancyCountdown(classroomId)
        ));
    }

    // ── Helper ────────────────────────────────────────────────────────────────
    private EnvironmentResponse toResponse(Classroom classroom, EnvironmentData data) {
        Long classroomId = classroom.getId();
        String occupancyStatus = automationEngine.getOccupancyStatus(classroomId);
        Long vacancyCountdown  = automationEngine.getVacancyCountdown(classroomId);

        if (data == null) {
            return EnvironmentResponse.builder()
                    .classroomId(classroomId)
                    .roomNumber(classroom.getRoomNumber())
                    .occupancyStatus(occupancyStatus)
                    .vacancyCountdownSeconds(vacancyCountdown)
                    .decisionSummary("No telemetry received yet")
                    .build();
        }
        return EnvironmentResponse.builder()
                .id(data.getId())
                .classroomId(classroomId)
                .roomNumber(classroom.getRoomNumber())
                .temperature(data.getTemperature())
                .humidity(data.getHumidity())
                .co2Level(data.getCo2Level())
                .lightLevel(data.getLightLevel())
                .noiseLevel(data.getNoiseLevel())
                .airQualityIndex(data.getAirQualityIndex())
                .roomState(data.getRoomState())
                .isDark(data.getIsDark())
                .gasLeak(data.getGasLeak())
                .gasPpm(data.getGasPpm())
                .fanStatus(data.getFanStatus())
                .lightStatus(data.getLightStatus())
                .ldrRaw(data.getLdrRaw())
                .motionDetected(data.getMotionDetected())
                .deviceId(data.getDeviceId())
                .hardwareClassroomId(data.getHardwareClassroomId())
                .occupancyStatus(occupancyStatus)
                .vacancyCountdownSeconds(vacancyCountdown)
                .decisionSummary(null) // Populated live during ingest, not in GET
                .recordedAt(data.getRecordedAt())
                .build();
    }

    // ── Record types ──────────────────────────────────────────────────────────

    public record EnvironmentIngestRequest(
            Long classroomId,
            String roomNumber,
            String esp32Id,
            Double temperature,
            Double humidity,
            Double lightIntensity,
            Double lightLevel,
            Double co2Level,
            Double noiseLevel,
            Double airQualityIndex,
            String roomState,       // "ACTIVE" or "EMPTY" (PIR motion)
            Boolean isDark,         // true / false
            Boolean gasLeak,        // true / false
            Double gasPpm,          // Gas sensor value
            String fanStatus,       // "ON" or "OFF"
            String lightStatus,     // "ON" or "OFF"
            Integer ldrRaw,         // Raw LDR ADC value
            Boolean motionDetected  // PIR sensor direct boolean
    ) {}

    public record TelemetryIngestResponse(
            String status,
            Long classroomId,
            String fanStatus,               // "ON" or "OFF" → sets fan relay on ESP32
            String lightStatus,             // "ON" or "OFF" → sets light relay on ESP32
            boolean gasEmergency,           // true if gas leak detected
            String occupancyStatus,         // "OCCUPIED", "COUNTDOWN", or "VACANT"
            Long vacancyCountdownSeconds,   // seconds remaining before VACANT (null if not in countdown)
            String decisionSummary,         // human-readable automation summary for ESP32 serial log
            List<AutomationDecisionResult> decisions
    ) {}

    public record OccupancyStatusResponse(
            Long classroomId,
            String occupancyStatus,
            Long vacancyCountdownSeconds
    ) {}
}
