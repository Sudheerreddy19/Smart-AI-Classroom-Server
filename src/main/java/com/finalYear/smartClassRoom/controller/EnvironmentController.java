package com.finalYear.smartClassRoom.controller;

import com.finalYear.smartClassRoom.dto.response.EnvironmentResponse;
import com.finalYear.smartClassRoom.entity.Classroom;
import com.finalYear.smartClassRoom.entity.EnvironmentData;
import com.finalYear.smartClassRoom.repository.ClassroomRepository;
import com.finalYear.smartClassRoom.repository.EnvironmentDataRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/environment")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class EnvironmentController {

    private final EnvironmentDataRepository environmentDataRepository;
    private final ClassroomRepository classroomRepository;

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
                .findByClassroom(classroom);
        // Return last `limit` records
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
    public ResponseEntity<String> ingest(@RequestBody EnvironmentIngestRequest req) {
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
            return ResponseEntity.badRequest().body("No active classroom found to associate sensor data with.");
        }

        Double light = req.lightIntensity() != null ? req.lightIntensity() : req.lightLevel();

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
                .build();

        environmentDataRepository.save(data);
        return ResponseEntity.ok("Telemetry received and stored successfully.");
    }

    // ── Helper ────────────────────────────────────────────────────────────────
    private EnvironmentResponse toResponse(Classroom classroom, EnvironmentData data) {
        if (data == null) {
            return EnvironmentResponse.builder()
                    .classroomId(classroom.getId())
                    .roomNumber(classroom.getRoomNumber())
                    .build();
        }
        return EnvironmentResponse.builder()
                .id(data.getId())
                .classroomId(classroom.getId())
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
                .recordedAt(data.getRecordedAt())
                .build();
    }

    // ── Record for ingest payload from ESP32 ──────────────────────────────────
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
            String lightStatus      // "ON" or "OFF"
    ) {}
}
