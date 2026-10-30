package com.finalYear.smartClassRoom.ai.decision;

import com.finalYear.smartClassRoom.entity.Classroom;
import com.finalYear.smartClassRoom.entity.Device;
import com.finalYear.smartClassRoom.entity.DeviceCommand;
import com.finalYear.smartClassRoom.entity.DeviceOverride;
import com.finalYear.smartClassRoom.entity.EnvironmentData;
import com.finalYear.smartClassRoom.repository.DeviceCommandRepository;
import com.finalYear.smartClassRoom.repository.DeviceRepository;
import com.finalYear.smartClassRoom.repository.EnvironmentDataRepository;
import com.finalYear.smartClassRoom.service.AuditLogService;
import com.finalYear.smartClassRoom.service.DeviceOverrideService;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Spring Boot Decision Engine — Sensor-Based Automation.
 *
 * Implements deterministic classroom automation based on real-time ESP32 sensor telemetry:
 * - Policy 1: Temperature-based Fan Control (>24.0°C -> ON, <=24.0°C -> OFF)
 * - Policy 2: Light-intensity-based Light Control (<=1700.0 -> ON, >1700.0 -> OFF)
 * - Policy 3: Sensor Data Freshness validation (Missing/stale/out-of-range -> INSUFFICIENT_DATA)
 * - Policy 4: Anti-Flapping & Cooldown (3-minute cooldown between state changes, no repeat commands)
 * - Policy 5: Durable Manual Overrides (Teacher overrides block automated toggles until expiration)
 * - Policy 6: Occupancy Safety Gate (PIR inactivity alone must NEVER establish empty; UNKNOWN never treated as EMPTY)
 * - Policy 7: Emergency Protection & Spark Prevention (Gas leak alerts without arcing relay switches)
 * - Policy 8: Command Lifecycle Tracking (CREATED -> DISPATCHED -> ACKNOWLEDGED)
 */
@Service
@Slf4j
public class ClassroomAutomationEngine {

    private final DeviceRepository deviceRepository;
    private final EnvironmentDataRepository environmentDataRepository;
    private final DeviceCommandRepository deviceCommandRepository;
    private final DeviceOverrideService deviceOverrideService;
    private final AuditLogService auditLogService;

    // Configurable thresholds with verified defaults
    @Getter @Setter
    private double temperatureThreshold;

    @Getter @Setter
    private double lightIntensityThreshold;

    @Getter @Setter
    private long cooldownSeconds;

    @Getter @Setter
    private long inactivitySeconds;

    @Getter @Setter
    private long maxFreshnessSeconds;

    // Clock instance to allow deterministic time simulation in unit tests
    @Setter
    private Clock clock = Clock.systemDefaultZone();

    // In-memory anti-flapping trackers
    // Key: "{classroomId}_{deviceType}" -> Instant of last successful state change
    private final Map<String, Instant> lastStateChangeMap = new ConcurrentHashMap<>();

    // Key: classroomId -> Instant of last confirmed PIR motion
    private final Map<Long, Instant> lastMotionDetectedMap = new ConcurrentHashMap<>();

    public ClassroomAutomationEngine(
            DeviceRepository deviceRepository,
            EnvironmentDataRepository environmentDataRepository,
            DeviceCommandRepository deviceCommandRepository,
            DeviceOverrideService deviceOverrideService,
            AuditLogService auditLogService,
            @Value("${app.automation.temperature-threshold:24.0}") double temperatureThreshold,
            @Value("${app.automation.light-threshold:1700.0}") double lightIntensityThreshold,
            @Value("${app.automation.cooldown-seconds:180}") long cooldownSeconds,
            @Value("${app.automation.inactivity-seconds:300}") long inactivitySeconds,
            @Value("${app.automation.max-freshness-seconds:600}") long maxFreshnessSeconds) {
        this.deviceRepository = deviceRepository;
        this.environmentDataRepository = environmentDataRepository;
        this.deviceCommandRepository = deviceCommandRepository;
        this.deviceOverrideService = deviceOverrideService;
        this.auditLogService = auditLogService;
        this.temperatureThreshold = temperatureThreshold;
        this.lightIntensityThreshold = lightIntensityThreshold;
        this.cooldownSeconds = cooldownSeconds;
        this.inactivitySeconds = inactivitySeconds;
        this.maxFreshnessSeconds = maxFreshnessSeconds;
    }

    /**
     * Primary entry point called when new ESP32 telemetry arrives.
     * Evaluates all policies deterministically and executes required device actions.
     */
    @Transactional
    public List<AutomationDecisionResult> evaluateTelemetry(Classroom classroom, EnvironmentData data) {
        if (classroom == null || data == null) {
            log.warn("Cannot evaluate automation: classroom or data is null");
            return Collections.emptyList();
        }

        Long classroomId = classroom.getId();
        Instant now = clock.instant();
        List<AutomationDecisionResult> results = new ArrayList<>();

        Map<String, Object> snapshot = buildSnapshot(data);

        // ── Policy 7: Emergency Protection & Spark Prevention ─────────────────
        boolean gasLeakActive = Boolean.TRUE.equals(data.getGasLeak())
                || (data.getGasPpm() != null && data.getGasPpm() > 400.0);

        if (gasLeakActive) {
            String emergencyReason = String.format(
                    "EMERGENCY: Gas leak detected! PPM=%.1f in %s. Relay switching disabled to prevent spark/arcing.",
                    data.getGasPpm() != null ? data.getGasPpm() : 0.0, classroom.getRoomNumber());
            log.error(emergencyReason);
            auditLogService.log("SAFETY_EMERGENCY", "Classroom", classroomId, emergencyReason);

            results.add(new AutomationDecisionResult(
                    "EMERGENCY_PROTECTION",
                    "EMERGENCY_SYSTEM",
                    "ALERT_ONLY",
                    AutomationDecisionStatus.ACTION_REQUIRED,
                    "NORMAL",
                    "EMERGENCY_ACTIVE",
                    emergencyReason,
                    snapshot,
                    LocalDateTime.now(clock),
                    true
            ));
            // In a gas emergency, do NOT cycle relays (risk of electrical arcing)
            return results;
        }

        // ── Policy 3: Data Freshness Check ────────────────────────────────────
        LocalDateTime recordedAt = data.getRecordedAt();
        if (recordedAt != null) {
            Duration age = Duration.between(recordedAt.atZone(clock.getZone()).toInstant(), now);
            if (age.getSeconds() > maxFreshnessSeconds) {
                String staleMsg = String.format(
                        "Telemetry data is stale (%d seconds old, limit=%d). Skipping automatic control.",
                        age.getSeconds(), maxFreshnessSeconds);
                log.info(staleMsg);
                results.add(new AutomationDecisionResult(
                        "DATA_FRESHNESS_GATE",
                        "ALL",
                        "RETAIN",
                        AutomationDecisionStatus.INSUFFICIENT_DATA,
                        "UNKNOWN",
                        "UNKNOWN",
                        staleMsg,
                        snapshot,
                        LocalDateTime.now(clock),
                        false
                ));
                return results; // Never issue commands on stale readings
            }
        }

        // ── PIR Motion & Occupancy Update ─────────────────────────────────────
        boolean motionDetected = Boolean.TRUE.equals(data.getMotionDetected())
                || "ACTIVE".equalsIgnoreCase(data.getRoomState());

        if (motionDetected) {
            lastMotionDetectedMap.put(classroomId, now);
        }

        // ── Policy 1: Temperature-Based Fan Control ───────────────────────────
        results.add(evaluateFanPolicy(classroom, data, now, snapshot));

        // ── Policy 2: Light-Intensity-Based Light Control ─────────────────────
        results.add(evaluateLightPolicy(classroom, data, now, motionDetected, snapshot));

        return results;
    }

    /**
     * Policy 1: Temperature-based fan control
     * - Temp > 24.0°C -> ON
     * - Temp <= 24.0°C -> OFF
     * - Check durable teacher override first
     * - Check anti-flapping cooldown (180s)
     */
    private AutomationDecisionResult evaluateFanPolicy(
            Classroom classroom, EnvironmentData data, Instant now, Map<String, Object> snapshot) {

        Double temp = data.getTemperature();

        // Range validation: plausible ambient temperature (-10°C to 60°C)
        if (temp == null || temp < -10.0 || temp > 60.0) {
            return new AutomationDecisionResult(
                    "TEMPERATURE_FAN_CONTROL",
                    "FAN",
                    "RETAIN",
                    AutomationDecisionStatus.INSUFFICIENT_DATA,
                    data.getFanStatus(),
                    data.getFanStatus(),
                    "Temperature reading is missing or out of plausible range (-10°C to 60°C)",
                    snapshot,
                    LocalDateTime.now(clock),
                    false
            );
        }

        String currentFanState = normalizeState(data.getFanStatus());

        // Policy 5: Check durable teacher override
        Optional<DeviceOverride> overrideOpt = deviceOverrideService.getActiveOverride(classroom, "FAN");
        if (overrideOpt.isPresent()) {
            DeviceOverride override = overrideOpt.get();
            String forcedState = override.getForcedState();
            return new AutomationDecisionResult(
                    "TEMPERATURE_FAN_CONTROL",
                    "FAN",
                    forcedState,
                    AutomationDecisionStatus.NO_ACTION_REQUIRED,
                    currentFanState,
                    forcedState,
                    String.format("Durable teacher override active: FAN forced %s until %s by %s (%s)",
                            forcedState, override.getExpiresAt(), override.getSetBy(), override.getReason()),
                    snapshot,
                    LocalDateTime.now(clock),
                    false
            );
        }

        // Deterministic threshold rule: > 24.0 -> ON; <= 24.0 -> OFF
        String desiredFanState = (temp > temperatureThreshold) ? "ON" : "OFF";

        // Never repeat command when already in desired state
        if (desiredFanState.equalsIgnoreCase(currentFanState)) {
            return new AutomationDecisionResult(
                    "TEMPERATURE_FAN_CONTROL",
                    "FAN",
                    desiredFanState,
                    AutomationDecisionStatus.NO_ACTION_REQUIRED,
                    currentFanState,
                    currentFanState,
                    String.format("Fan is already in requested state (%s). Temperature=%.1f°C (Threshold=%.1f°C).",
                            desiredFanState, temp, temperatureThreshold),
                    snapshot,
                    LocalDateTime.now(clock),
                    false
            );
        }

        // Policy 4: Anti-Flapping Cooldown Check (3 minutes / 180s)
        String cooldownKey = classroom.getId() + "_FAN";
        Instant lastChange = lastStateChangeMap.get(cooldownKey);
        if (lastChange != null) {
            long elapsedSeconds = Duration.between(lastChange, now).getSeconds();
            if (elapsedSeconds < cooldownSeconds) {
                return new AutomationDecisionResult(
                        "TEMPERATURE_FAN_CONTROL",
                        "FAN",
                        desiredFanState,
                        AutomationDecisionStatus.COOLDOWN_ACTIVE,
                        currentFanState,
                        currentFanState,
                        String.format("Cooldown active for FAN (%d/%d seconds elapsed). Command deferred to prevent flapping.",
                                elapsedSeconds, cooldownSeconds),
                        snapshot,
                        LocalDateTime.now(clock),
                        false
                );
            }
        }

        // Execute state change with command lifecycle tracking
        boolean executed = applyDeviceState(classroom, "FAN", desiredFanState,
                String.format("Automatic fan control: Temp=%.1f°C, Threshold=%.1f°C", temp, temperatureThreshold));

        if (executed) {
            lastStateChangeMap.put(cooldownKey, now);
            data.setFanStatus(desiredFanState);
            environmentDataRepository.save(data);

            auditLogService.log("AUTO_FAN_CONTROL", "Classroom", classroom.getId(),
                    String.format("Automatic fan control: set %s (Temp=%.1f°C, Threshold=%.1f°C)",
                            desiredFanState, temp, temperatureThreshold));

            return new AutomationDecisionResult(
                    "TEMPERATURE_FAN_CONTROL",
                    "FAN",
                    desiredFanState,
                    AutomationDecisionStatus.EXECUTION_SUCCESS,
                    currentFanState,
                    desiredFanState,
                    String.format("Successfully switched fan %s (Temp=%.1f°C)", desiredFanState, temp),
                    snapshot,
                    LocalDateTime.now(clock),
                    true
            );
        } else {
            return new AutomationDecisionResult(
                    "TEMPERATURE_FAN_CONTROL",
                    "FAN",
                    desiredFanState,
                    AutomationDecisionStatus.EXECUTION_FAILED,
                    currentFanState,
                    currentFanState,
                    "Failed to update fan state in database/hardware",
                    snapshot,
                    LocalDateTime.now(clock),
                    false
            );
        }
    }

    /**
     * Policy 2: Light-intensity-based light control
     * - Light <= 1700.0 -> ON
     * - Light > 1700.0 -> OFF
     * - Check durable teacher override first
     * - Policy 6: PIR inactivity alone must NEVER establish EMPTY; UNKNOWN never treated as EMPTY
     * - Check anti-flapping cooldown (180s)
     */
    private AutomationDecisionResult evaluateLightPolicy(
            Classroom classroom, EnvironmentData data, Instant now, boolean motionDetected, Map<String, Object> snapshot) {

        Long classroomId = classroom.getId();
        String currentLightState = normalizeState(data.getLightStatus());

        // Policy 5: Check durable teacher override
        Optional<DeviceOverride> overrideOpt = deviceOverrideService.getActiveOverride(classroom, "LIGHT");
        if (overrideOpt.isPresent()) {
            DeviceOverride override = overrideOpt.get();
            String forcedState = override.getForcedState();
            return new AutomationDecisionResult(
                    "LIGHT_INTENSITY_CONTROL",
                    "LIGHT",
                    forcedState,
                    AutomationDecisionStatus.NO_ACTION_REQUIRED,
                    currentLightState,
                    forcedState,
                    String.format("Durable teacher override active: LIGHT forced %s until %s by %s (%s)",
                            forcedState, override.getExpiresAt(), override.getSetBy(), override.getReason()),
                    snapshot,
                    LocalDateTime.now(clock),
                    false
            );
        }

        // Policy 6: Occupancy Safety Gate
        // Master Prompt: PIR inactivity alone must NEVER establish classroom is empty.
        // UNKNOWN occupancy must NEVER be treated as EMPTY.
        // Therefore, we only evaluate light intensity rather than abruptly killing lights based solely on PIR quietness.
        Double lightIntensity = resolveLightIntensity(data);
        if (lightIntensity == null || lightIntensity < 0.0) {
            return new AutomationDecisionResult(
                    "LIGHT_INTENSITY_CONTROL",
                    "LIGHT",
                    "RETAIN",
                    AutomationDecisionStatus.INSUFFICIENT_DATA,
                    currentLightState,
                    currentLightState,
                    "Light intensity sensor reading is missing or invalid",
                    snapshot,
                    LocalDateTime.now(clock),
                    false
            );
        }

        // Deterministic threshold rule: <= 1700 -> ON (dark); > 1700 -> OFF (bright enough)
        String desiredLightState = (lightIntensity <= lightIntensityThreshold) ? "ON" : "OFF";

        // Never repeat command when already in desired state
        if (desiredLightState.equalsIgnoreCase(currentLightState)) {
            return new AutomationDecisionResult(
                    "LIGHT_INTENSITY_CONTROL",
                    "LIGHT",
                    desiredLightState,
                    AutomationDecisionStatus.NO_ACTION_REQUIRED,
                    currentLightState,
                    currentLightState,
                    String.format("Light is already in requested state (%s). Intensity=%.1f (Threshold=%.1f).",
                            desiredLightState, lightIntensity, lightIntensityThreshold),
                    snapshot,
                    LocalDateTime.now(clock),
                    false
            );
        }

        // Cooldown check (3 minutes / 180s)
        String cooldownKey = classroomId + "_LIGHT";
        Instant lastChange = lastStateChangeMap.get(cooldownKey);
        if (lastChange != null) {
            long elapsedSeconds = Duration.between(lastChange, now).getSeconds();
            if (elapsedSeconds < cooldownSeconds) {
                return new AutomationDecisionResult(
                        "LIGHT_INTENSITY_CONTROL",
                        "LIGHT",
                        desiredLightState,
                        AutomationDecisionStatus.COOLDOWN_ACTIVE,
                        currentLightState,
                        currentLightState,
                        String.format("Cooldown active for LIGHT (%d/%d seconds elapsed). Command deferred to prevent flapping.",
                                elapsedSeconds, cooldownSeconds),
                        snapshot,
                        LocalDateTime.now(clock),
                        false
                );
            }
        }

        boolean executed = applyDeviceState(classroom, "LIGHT", desiredLightState,
                String.format("Automatic light control: Intensity=%.1f, Threshold=%.1f", lightIntensity, lightIntensityThreshold));

        if (executed) {
            lastStateChangeMap.put(cooldownKey, now);
            data.setLightStatus(desiredLightState);
            environmentDataRepository.save(data);

            auditLogService.log("AUTO_LIGHT_CONTROL", "Classroom", classroomId,
                    String.format("Automatic light control: set %s (Intensity=%.1f, Threshold=%.1f)",
                            desiredLightState, lightIntensity, lightIntensityThreshold));

            return new AutomationDecisionResult(
                    "LIGHT_INTENSITY_CONTROL",
                    "LIGHT",
                    desiredLightState,
                    AutomationDecisionStatus.EXECUTION_SUCCESS,
                    currentLightState,
                    desiredLightState,
                    String.format("Successfully switched light %s (Intensity=%.1f)", desiredLightState, lightIntensity),
                    snapshot,
                    LocalDateTime.now(clock),
                    true
            );
        } else {
            return new AutomationDecisionResult(
                    "LIGHT_INTENSITY_CONTROL",
                    "LIGHT",
                    desiredLightState,
                    AutomationDecisionStatus.EXECUTION_FAILED,
                    currentLightState,
                    currentLightState,
                    "Failed to update light state in database/hardware",
                    snapshot,
                    LocalDateTime.now(clock),
                    false
            );
        }
    }

    /**
     * Applies state changes to matching devices registered in the classroom
     * and logs the command lifecycle record (CREATED -> DISPATCHED -> ACKNOWLEDGED).
     */
    private boolean applyDeviceState(Classroom classroom, String targetType, String action, String reason) {
        String commandUuid = UUID.randomUUID().toString();
        LocalDateTime now = LocalDateTime.now(clock);

        DeviceCommand cmd = DeviceCommand.builder()
                .commandId(commandUuid)
                .classroom(classroom)
                .targetType(targetType)
                .action(action)
                .issuedBy("AUTOMATION_ENGINE")
                .reason(reason)
                .status(DeviceCommand.CommandStatus.CREATED)
                .issuedAt(now)
                .build();

        try {
            cmd = deviceCommandRepository.save(cmd);
            cmd.setStatus(DeviceCommand.CommandStatus.DISPATCHED);
            cmd.setDispatchedAt(now);

            Device.DeviceStatus targetStatus = "ON".equalsIgnoreCase(action)
                    ? Device.DeviceStatus.ONLINE
                    : Device.DeviceStatus.OFFLINE;

            List<Device> devices = deviceRepository.findByClassroom(classroom);
            int matched = 0;
            for (Device d : devices) {
                String name = d.getDeviceName() != null ? d.getDeviceName().toUpperCase(Locale.ROOT) : "";
                String type = d.getDeviceType() != null ? d.getDeviceType().name() : "";

                if (name.contains(targetType) || type.contains(targetType) || d.getDeviceType() == Device.DeviceType.RELAY) {
                    d.setStatus(targetStatus);
                    deviceRepository.save(d);
                    matched++;
                }
            }

            cmd.setStatus(DeviceCommand.CommandStatus.ACKNOWLEDGED);
            cmd.setAcknowledgedAt(LocalDateTime.now(clock));
            deviceCommandRepository.save(cmd);
            return true;
        } catch (Exception e) {
            log.error("Error applying device state for {}: {}", targetType, e.getMessage(), e);
            cmd.setStatus(DeviceCommand.CommandStatus.FAILED);
            cmd.setErrorMessage(e.getMessage());
            deviceCommandRepository.save(cmd);
            return false;
        }
    }

    private Double resolveLightIntensity(EnvironmentData data) {
        if (data.getLightLevel() != null) return data.getLightLevel();
        if (data.getLdrRaw() != null) return data.getLdrRaw().doubleValue();
        return null;
    }

    private String normalizeState(String state) {
        if (state == null || state.isBlank()) return "OFF";
        return state.trim().toUpperCase(Locale.ROOT);
    }

    private Map<String, Object> buildSnapshot(EnvironmentData data) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("temperature", data.getTemperature());
        map.put("lightLevel", data.getLightLevel());
        map.put("ldrRaw", data.getLdrRaw());
        map.put("motionDetected", data.getMotionDetected());
        map.put("roomState", data.getRoomState());
        map.put("fanStatus", data.getFanStatus());
        map.put("lightStatus", data.getLightStatus());
        map.put("gasLeak", data.getGasLeak());
        map.put("gasPpm", data.getGasPpm());
        return map;
    }

    /**
     * Resets internal trackers — useful for testing and state resets.
     */
    public void resetTrackers() {
        lastStateChangeMap.clear();
        lastMotionDetectedMap.clear();
    }
}
