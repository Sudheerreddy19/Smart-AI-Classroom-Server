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
 * Automation Priority Order (highest to lowest):
 *   1. Gas Emergency Protection  — blocks relay switching to prevent arcing
 *   2. Data Freshness Gate       — skips control on stale/missing readings
 *   3. PIR Vacancy Override      — 60-second timer forces FAN+LIGHT OFF; blocks temp/LDR automation
 *   4. Temperature Fan Policy    — temp > 24.0°C → ON; ≤ 24.0°C → OFF (immediate, no cooldown)
 *   5. LDR Light Policy          — LDR ≤ 1700 → ON; > 1700 → OFF (immediate, no cooldown)
 *
 * Teacher overrides (durable, persisted) block policies 4 and 5.
 * PIR vacancy state (OCCUPIED / COUNTDOWN / VACANT) is held in-memory per classroom.
 */
@Service
@Slf4j
public class ClassroomAutomationEngine {

    // ── Occupancy state enum ───────────────────────────────────────────────────
    public enum OccupancyState { OCCUPIED, COUNTDOWN, VACANT }

    /** Per-classroom occupancy tracking record. */
    private static class OccupancyTracker {
        OccupancyState state = OccupancyState.OCCUPIED; // default: assume occupied at startup
        Instant vacancyStarted = null;                  // set when countdown begins

        OccupancyTracker() {}
    }

    // ── Dependencies ──────────────────────────────────────────────────────────
    private final DeviceRepository deviceRepository;
    private final EnvironmentDataRepository environmentDataRepository;
    private final DeviceCommandRepository deviceCommandRepository;
    private final DeviceOverrideService deviceOverrideService;
    private final AuditLogService auditLogService;

    // ── Configurable thresholds ───────────────────────────────────────────────
    @Getter @Setter private double temperatureThreshold;
    @Getter @Setter private double lightIntensityThreshold;
    @Getter @Setter private long cooldownSeconds;       // kept for backward-compat; effectively 0
    @Getter @Setter private long inactivitySeconds;
    @Getter @Setter private long maxFreshnessSeconds;
    @Getter @Setter private long vacancyTimeoutSeconds; // seconds of no-motion before VACANT

    // ── Clock (overridable for deterministic unit tests) ──────────────────────
    @Setter
    private Clock clock = Clock.systemDefaultZone();

    // ── In-memory state maps ──────────────────────────────────────────────────
    /** Key: "{classroomId}_{deviceType}" → Instant of last state change (anti-flapping) */
    private final Map<String, Instant> lastStateChangeMap = new ConcurrentHashMap<>();

    /** Key: classroomId → OccupancyTracker */
    private final Map<Long, OccupancyTracker> occupancyTrackerMap = new ConcurrentHashMap<>();

    // ── Constructor ───────────────────────────────────────────────────────────
    public ClassroomAutomationEngine(
            DeviceRepository deviceRepository,
            EnvironmentDataRepository environmentDataRepository,
            DeviceCommandRepository deviceCommandRepository,
            DeviceOverrideService deviceOverrideService,
            AuditLogService auditLogService,
            @Value("${app.automation.temperature-threshold:24.0}") double temperatureThreshold,
            @Value("${app.automation.light-threshold:1700.0}") double lightIntensityThreshold,
            @Value("${app.automation.cooldown-seconds:0}") long cooldownSeconds,
            @Value("${app.automation.inactivity-seconds:60}") long inactivitySeconds,
            @Value("${app.automation.max-freshness-seconds:600}") long maxFreshnessSeconds,
            @Value("${app.automation.vacancy-timeout-seconds:60}") long vacancyTimeoutSeconds) {
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
        this.vacancyTimeoutSeconds = vacancyTimeoutSeconds;
    }

    // ── Public occupancy query methods (for dashboard / controller) ───────────

    /**
     * Returns "OCCUPIED", "COUNTDOWN", or "VACANT" for the given classroom.
     * Returns "OCCUPIED" by default if no telemetry has been received yet.
     */
    public String getOccupancyStatus(Long classroomId) {
        OccupancyTracker tracker = occupancyTrackerMap.get(classroomId);
        if (tracker == null) return "OCCUPIED";
        return tracker.state.name();
    }

    /**
     * Returns seconds remaining in the vacancy countdown, or null if not in COUNTDOWN state.
     */
    public Long getVacancyCountdown(Long classroomId) {
        OccupancyTracker tracker = occupancyTrackerMap.get(classroomId);
        if (tracker == null || tracker.state != OccupancyState.COUNTDOWN || tracker.vacancyStarted == null) {
            return null;
        }
        long elapsed = Duration.between(tracker.vacancyStarted, clock.instant()).getSeconds();
        long remaining = vacancyTimeoutSeconds - elapsed;
        return Math.max(0L, remaining);
    }

    // ── Primary evaluation entry point ────────────────────────────────────────

    /**
     * Called on every ESP32 telemetry ingest.
     * Evaluates all automation policies in priority order.
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

        // ── PRIORITY 1: Gas Emergency Protection ──────────────────────────────
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
            return results; // Do NOT cycle relays — risk of electrical arcing during gas leak
        }

        // ── PRIORITY 2: Data Freshness Gate ───────────────────────────────────
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
                return results;
            }
        }

        // ── PRIORITY 3: PIR Vacancy Timer ─────────────────────────────────────
        boolean motionDetected = Boolean.TRUE.equals(data.getMotionDetected())
                || "ACTIVE".equalsIgnoreCase(data.getRoomState());

        OccupancyTracker tracker = occupancyTrackerMap.computeIfAbsent(classroomId, id -> new OccupancyTracker());

        if (motionDetected) {
            // Motion detected → classroom is OCCUPIED; reset any countdown
            if (tracker.state != OccupancyState.OCCUPIED) {
                log.info("Classroom {} — motion detected, restoring OCCUPIED state (was {})",
                        classroom.getRoomNumber(), tracker.state);
                auditLogService.log("PIR_MOTION", "Classroom", classroomId,
                        "PIR motion detected — classroom marked OCCUPIED, normal automation restored.");
            }
            tracker.state = OccupancyState.OCCUPIED;
            tracker.vacancyStarted = null;

            results.add(new AutomationDecisionResult(
                    "PIR_OCCUPANCY",
                    "OCCUPANCY",
                    "OCCUPIED",
                    AutomationDecisionStatus.NO_ACTION_REQUIRED,
                    "OCCUPIED",
                    "OCCUPIED",
                    "Motion detected — classroom is OCCUPIED. Normal temp/LDR automation active.",
                    snapshot,
                    LocalDateTime.now(clock),
                    false
            ));

        } else {
            // No motion detected
            if (tracker.state == OccupancyState.OCCUPIED) {
                // Transition: OCCUPIED → COUNTDOWN
                tracker.state = OccupancyState.COUNTDOWN;
                tracker.vacancyStarted = now;
                log.info("Classroom {} — no motion, starting {}-second vacancy countdown.",
                        classroom.getRoomNumber(), vacancyTimeoutSeconds);
                auditLogService.log("PIR_COUNTDOWN_START", "Classroom", classroomId,
                        String.format("No PIR motion — starting %d-second vacancy countdown.", vacancyTimeoutSeconds));
            }

            if (tracker.state == OccupancyState.COUNTDOWN) {
                long elapsed = (tracker.vacancyStarted != null)
                        ? Duration.between(tracker.vacancyStarted, now).getSeconds()
                        : vacancyTimeoutSeconds;
                long remaining = vacancyTimeoutSeconds - elapsed;

                if (remaining > 0) {
                    // Still in countdown — do NOT turn devices off yet
                    String countdownMsg = String.format(
                            "No motion — vacancy countdown: %d/%d seconds elapsed, %d seconds remaining.",
                            elapsed, vacancyTimeoutSeconds, remaining);
                    log.debug(countdownMsg);
                    results.add(new AutomationDecisionResult(
                            "PIR_OCCUPANCY",
                            "OCCUPANCY",
                            "COUNTDOWN",
                            AutomationDecisionStatus.NO_ACTION_REQUIRED,
                            "COUNTDOWN",
                            "COUNTDOWN",
                            countdownMsg,
                            snapshot,
                            LocalDateTime.now(clock),
                            false
                    ));
                    // Continue to normal temp/LDR automation during countdown
                } else {
                    // Countdown expired → VACANT
                    if (tracker.state == OccupancyState.COUNTDOWN) {
                        tracker.state = OccupancyState.VACANT;
                        log.info("Classroom {} — vacancy timer expired ({} seconds). Marking VACANT. Turning off FAN and LIGHT.",
                                classroom.getRoomNumber(), vacancyTimeoutSeconds);
                        auditLogService.log("PIR_VACANT", "Classroom", classroomId,
                                String.format("Classroom marked VACANT after %d seconds without motion. FAN and LIGHT forced OFF.",
                                        vacancyTimeoutSeconds));
                    }
                }
            }

            if (tracker.state == OccupancyState.VACANT) {
                // Force FAN OFF
                applyDeviceState(classroom, "FAN", "OFF",
                        "PIR vacancy: classroom vacant for " + vacancyTimeoutSeconds + "s, forcing FAN OFF.");
                data.setFanStatus("OFF");

                // Force LIGHT OFF
                applyDeviceState(classroom, "LIGHT", "OFF",
                        "PIR vacancy: classroom vacant for " + vacancyTimeoutSeconds + "s, forcing LIGHT OFF.");
                data.setLightStatus("OFF");

                environmentDataRepository.save(data);

                String vacantMsg = String.format(
                        "Classroom VACANT (no motion for ≥%d seconds). FAN and LIGHT forced OFF. Automation blocked.",
                        vacancyTimeoutSeconds);

                results.add(new AutomationDecisionResult(
                        "PIR_OCCUPANCY",
                        "FAN+LIGHT",
                        "OFF",
                        AutomationDecisionStatus.EXECUTION_SUCCESS,
                        "UNKNOWN",
                        "OFF",
                        vacantMsg,
                        snapshot,
                        LocalDateTime.now(clock),
                        true
                ));

                // RETURN EARLY — temp/LDR automation is blocked while VACANT
                return results;
            }
        }

        // ── PRIORITY 4: Temperature-Based Fan Control ──────────────────────────
        // Only reached when classroom is OCCUPIED or in COUNTDOWN
        results.add(evaluateFanPolicy(classroom, data, now, snapshot));

        // ── PRIORITY 5: LDR-Based Light Control ───────────────────────────────
        // Only reached when classroom is OCCUPIED or in COUNTDOWN
        results.add(evaluateLightPolicy(classroom, data, now, snapshot));

        return results;
    }

    // ── Policy: Temperature-Based Fan Control ─────────────────────────────────

    /**
     * Temp > 24.0°C → FAN ON; ≤ 24.0°C → FAN OFF.
     * Immediate — no cooldown (cooldownSeconds = 0 in config).
     * Teacher overrides block this policy.
     */
    private AutomationDecisionResult evaluateFanPolicy(
            Classroom classroom, EnvironmentData data, Instant now, Map<String, Object> snapshot) {

        Double temp = data.getTemperature();

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

        // Check durable teacher override
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

        // Deterministic threshold: > 24.0 → ON; ≤ 24.0 → OFF
        String desiredFanState = (temp > temperatureThreshold) ? "ON" : "OFF";

        if (desiredFanState.equalsIgnoreCase(currentFanState)) {
            return new AutomationDecisionResult(
                    "TEMPERATURE_FAN_CONTROL",
                    "FAN",
                    desiredFanState,
                    AutomationDecisionStatus.NO_ACTION_REQUIRED,
                    currentFanState,
                    currentFanState,
                    String.format("Fan already in desired state (%s). Temp=%.1f°C (Threshold=%.1f°C).",
                            desiredFanState, temp, temperatureThreshold),
                    snapshot,
                    LocalDateTime.now(clock),
                    false
            );
        }

        // Cooldown check (effectively disabled since cooldownSeconds=0, but kept for safety)
        String cooldownKey = classroom.getId() + "_FAN";
        Instant lastChange = lastStateChangeMap.get(cooldownKey);
        if (lastChange != null && cooldownSeconds > 0) {
            long elapsedSeconds = Duration.between(lastChange, now).getSeconds();
            if (elapsedSeconds < cooldownSeconds) {
                return new AutomationDecisionResult(
                        "TEMPERATURE_FAN_CONTROL",
                        "FAN",
                        desiredFanState,
                        AutomationDecisionStatus.COOLDOWN_ACTIVE,
                        currentFanState,
                        currentFanState,
                        String.format("Cooldown active for FAN (%d/%d seconds elapsed).", elapsedSeconds, cooldownSeconds),
                        snapshot,
                        LocalDateTime.now(clock),
                        false
                );
            }
        }

        boolean executed = applyDeviceState(classroom, "FAN", desiredFanState,
                String.format("Auto fan control: Temp=%.1f°C, Threshold=%.1f°C", temp, temperatureThreshold));

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

    // ── Policy: LDR-Based Light Control ───────────────────────────────────────

    /**
     * LDR ≤ 1700 → LIGHT ON; > 1700 → LIGHT OFF.
     * Immediate — no cooldown.
     * Teacher overrides block this policy.
     */
    private AutomationDecisionResult evaluateLightPolicy(
            Classroom classroom, EnvironmentData data, Instant now, Map<String, Object> snapshot) {

        Long classroomId = classroom.getId();
        String currentLightState = normalizeState(data.getLightStatus());

        // Check durable teacher override
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

        // Deterministic threshold: ≤ 1700 → ON (dark); > 1700 → OFF (bright)
        String desiredLightState = (lightIntensity <= lightIntensityThreshold) ? "ON" : "OFF";

        if (desiredLightState.equalsIgnoreCase(currentLightState)) {
            return new AutomationDecisionResult(
                    "LIGHT_INTENSITY_CONTROL",
                    "LIGHT",
                    desiredLightState,
                    AutomationDecisionStatus.NO_ACTION_REQUIRED,
                    currentLightState,
                    currentLightState,
                    String.format("Light already in desired state (%s). Intensity=%.1f (Threshold=%.1f).",
                            desiredLightState, lightIntensity, lightIntensityThreshold),
                    snapshot,
                    LocalDateTime.now(clock),
                    false
            );
        }

        // Cooldown check (effectively disabled since cooldownSeconds=0)
        String cooldownKey = classroomId + "_LIGHT";
        Instant lastChange = lastStateChangeMap.get(cooldownKey);
        if (lastChange != null && cooldownSeconds > 0) {
            long elapsedSeconds = Duration.between(lastChange, now).getSeconds();
            if (elapsedSeconds < cooldownSeconds) {
                return new AutomationDecisionResult(
                        "LIGHT_INTENSITY_CONTROL",
                        "LIGHT",
                        desiredLightState,
                        AutomationDecisionStatus.COOLDOWN_ACTIVE,
                        currentLightState,
                        currentLightState,
                        String.format("Cooldown active for LIGHT (%d/%d seconds elapsed).", elapsedSeconds, cooldownSeconds),
                        snapshot,
                        LocalDateTime.now(clock),
                        false
                );
            }
        }

        boolean executed = applyDeviceState(classroom, "LIGHT", desiredLightState,
                String.format("Auto light control: Intensity=%.1f, Threshold=%.1f", lightIntensity, lightIntensityThreshold));

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

    // ── Device Command Execution ───────────────────────────────────────────────

    /**
     * Writes device state to DB and creates full command lifecycle record
     * (CREATED → DISPATCHED → ACKNOWLEDGED).
     * Physical relay actuation happens via the ESP32 reading the telemetry response.
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
            for (Device d : devices) {
                String name = d.getDeviceName() != null ? d.getDeviceName().toUpperCase(Locale.ROOT) : "";
                String type = d.getDeviceType() != null ? d.getDeviceType().name() : "";

                if (name.contains(targetType) || type.contains(targetType) || d.getDeviceType() == Device.DeviceType.RELAY) {
                    d.setStatus(targetStatus);
                    deviceRepository.save(d);
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

    // ── Helpers ───────────────────────────────────────────────────────────────

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
     * Resets all in-memory trackers. Used in tests and state resets.
     */
    public void resetTrackers() {
        lastStateChangeMap.clear();
        occupancyTrackerMap.clear();
    }
}
