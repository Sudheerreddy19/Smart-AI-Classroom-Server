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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class ClassroomAutomationEngineTest {

    @Mock private DeviceRepository deviceRepository;
    @Mock private EnvironmentDataRepository environmentDataRepository;
    @Mock private DeviceCommandRepository deviceCommandRepository;
    @Mock private DeviceOverrideService deviceOverrideService;
    @Mock private AuditLogService auditLogService;

    private ClassroomAutomationEngine engine;
    private Classroom classroom;
    private List<Device> classroomDevices;

    private final Instant BASE = Instant.parse("2026-10-26T12:00:00Z");
    private final ZoneId UTC   = ZoneId.of("UTC");

    /** Vacancy timeout used in tests — keeps tests fast */
    private static final long VACANCY_TIMEOUT = 60L;

    @BeforeEach
    void setUp() {
        engine = new ClassroomAutomationEngine(
                deviceRepository,
                environmentDataRepository,
                deviceCommandRepository,
                deviceOverrideService,
                auditLogService,
                24.0,           // temp threshold
                1700.0,         // LDR threshold
                0L,             // cooldown (disabled)
                60L,            // inactivity seconds
                600L,           // max freshness seconds
                VACANCY_TIMEOUT // vacancy timeout
        );
        engine.setClock(Clock.fixed(BASE, UTC));
        engine.resetTrackers();

        classroom = Classroom.builder()
                .id(1L)
                .roomNumber("A1")
                .active(true)
                .build();

        classroomDevices = new ArrayList<>();
        classroomDevices.add(Device.builder()
                .id(101L).deviceId("FAN_RELAY_01").deviceName("Classroom Fan")
                .deviceType(Device.DeviceType.RELAY).classroom(classroom)
                .status(Device.DeviceStatus.OFFLINE).build());
        classroomDevices.add(Device.builder()
                .id(102L).deviceId("LIGHT_RELAY_01").deviceName("Classroom Light")
                .deviceType(Device.DeviceType.RELAY).classroom(classroom)
                .status(Device.DeviceStatus.OFFLINE).build());

        lenient().when(deviceRepository.findByClassroom(classroom)).thenReturn(classroomDevices);
        lenient().when(environmentDataRepository.save(any(EnvironmentData.class))).thenAnswer(i -> i.getArgument(0));
        lenient().when(deviceCommandRepository.save(any(DeviceCommand.class))).thenAnswer(i -> i.getArgument(0));
        lenient().when(deviceOverrideService.getActiveOverride(any(), any())).thenReturn(Optional.empty());
    }

    // ── Scenario 1: Hot + dark + motion → FAN ON, LIGHT ON, OCCUPIED ──────────
    @Test
    @DisplayName("S1: Temp 29°C, LDR 1000, PIR motion → FAN ON, LIGHT ON, OCCUPIED")
    void s1_hotDarkWithMotion_fanOnLightOn() {
        EnvironmentData data = EnvironmentData.builder()
                .classroom(classroom).temperature(29.0)
                .fanStatus("OFF").lightStatus("OFF").lightLevel(1000.0)
                .motionDetected(true)
                .recordedAt(LocalDateTime.ofInstant(BASE, UTC)).build();

        List<AutomationDecisionResult> results = engine.evaluateTelemetry(classroom, data);

        assertOccupancy(results, "OCCUPIED");
        assertFan(results, AutomationDecisionStatus.EXECUTION_SUCCESS, "ON");
        assertLight(results, AutomationDecisionStatus.EXECUTION_SUCCESS, "ON");
        assertEquals("OCCUPIED", engine.getOccupancyStatus(1L));
        assertNull(engine.getVacancyCountdown(1L));
    }

    // ── Scenario 2: Hot + bright + motion → FAN ON, LIGHT OFF, OCCUPIED ───────
    @Test
    @DisplayName("S2: Temp 29°C, LDR 3931, PIR motion → FAN ON, LIGHT OFF, OCCUPIED")
    void s2_hotBrightWithMotion_fanOnLightOff() {
        EnvironmentData data = EnvironmentData.builder()
                .classroom(classroom).temperature(29.0)
                .fanStatus("OFF").lightStatus("ON").lightLevel(3931.0)
                .motionDetected(true)
                .recordedAt(LocalDateTime.ofInstant(BASE, UTC)).build();

        List<AutomationDecisionResult> results = engine.evaluateTelemetry(classroom, data);

        assertOccupancy(results, "OCCUPIED");
        assertFan(results, AutomationDecisionStatus.EXECUTION_SUCCESS, "ON");
        assertLight(results, AutomationDecisionStatus.EXECUTION_SUCCESS, "OFF");
        assertEquals("OCCUPIED", engine.getOccupancyStatus(1L));
    }

    // ── Scenario 3: PIR stops → countdown starts, devices NOT switched off ─────
    @Test
    @DisplayName("S3: PIR stops → countdown starts, FAN and LIGHT not immediately forced OFF")
    void s3_pirStops_countdownStarts_devicesNotOff() {
        // First: establish OCCUPIED with motion
        EnvironmentData motionData = EnvironmentData.builder()
                .classroom(classroom).temperature(29.0)
                .fanStatus("OFF").lightStatus("OFF").lightLevel(1000.0)
                .motionDetected(true)
                .recordedAt(LocalDateTime.ofInstant(BASE, UTC)).build();
        engine.evaluateTelemetry(classroom, motionData);

        // Now: no motion at T+5s (well within 60s countdown)
        Instant t5 = BASE.plusSeconds(5);
        engine.setClock(Clock.fixed(t5, UTC));

        EnvironmentData noMotionData = EnvironmentData.builder()
                .classroom(classroom).temperature(29.0)
                .fanStatus("ON").lightStatus("ON").lightLevel(1000.0)
                .motionDetected(false)
                .recordedAt(LocalDateTime.ofInstant(t5, UTC)).build();

        List<AutomationDecisionResult> results = engine.evaluateTelemetry(classroom, noMotionData);

        assertEquals("COUNTDOWN", engine.getOccupancyStatus(1L));
        Long countdown = engine.getVacancyCountdown(1L);
        assertNotNull(countdown);
        assertTrue(countdown > 0, "Countdown should still be positive at T+5s");

        // Automation should still run (temp/LDR) — devices not forced OFF during countdown
        assertFalse(results.stream().anyMatch(r ->
                "PIR_OCCUPANCY".equals(r.ruleName()) && "OFF".equals(r.requestedAction())));
    }

    // ── Scenario 4: Motion at 30s → countdown resets, stays OCCUPIED ──────────
    @Test
    @DisplayName("S4: Motion at T+30s during countdown → resets countdown, stays OCCUPIED")
    void s4_motionAt30s_resetsCountdown() {
        // Establish OCCUPIED
        EnvironmentData d0 = makeData(29.0, 1000.0, true, BASE);
        engine.evaluateTelemetry(classroom, d0);

        // No motion at T+10s → countdown starts
        Instant t10 = BASE.plusSeconds(10);
        engine.setClock(Clock.fixed(t10, UTC));
        engine.evaluateTelemetry(classroom, makeData(29.0, 1000.0, false, t10));
        assertEquals("COUNTDOWN", engine.getOccupancyStatus(1L));

        // Motion detected at T+30s → back to OCCUPIED
        Instant t30 = BASE.plusSeconds(30);
        engine.setClock(Clock.fixed(t30, UTC));
        engine.evaluateTelemetry(classroom, makeData(29.0, 1000.0, true, t30));

        assertEquals("OCCUPIED", engine.getOccupancyStatus(1L));
        assertNull(engine.getVacancyCountdown(1L));
    }

    // ── Scenario 5: No motion for 60s → VACANT, FAN OFF, LIGHT OFF ────────────
    @Test
    @DisplayName("S5: No motion for 60+ seconds → VACANT declared, FAN OFF, LIGHT OFF")
    void s5_noMotion60s_vacant_fanOffLightOff() {
        // Start OCCUPIED
        engine.evaluateTelemetry(classroom, makeData(29.0, 1000.0, true, BASE));

        // No motion at T+1s (starts countdown)
        Instant t1 = BASE.plusSeconds(1);
        engine.setClock(Clock.fixed(t1, UTC));
        engine.evaluateTelemetry(classroom, makeData(29.0, 1000.0, false, t1));

        // Still no motion at T+65s (after vacancy timeout)
        Instant t65 = BASE.plusSeconds(65);
        engine.setClock(Clock.fixed(t65, UTC));
        EnvironmentData hotDark = makeData(29.0, 1000.0, false, t65);
        hotDark.setFanStatus("ON");
        hotDark.setLightStatus("ON");

        List<AutomationDecisionResult> results = engine.evaluateTelemetry(classroom, hotDark);

        assertEquals("VACANT", engine.getOccupancyStatus(1L));

        // FAN and LIGHT must be forced OFF
        assertTrue(results.stream().anyMatch(r ->
                "PIR_OCCUPANCY".equals(r.ruleName()) &&
                "OFF".equals(r.requestedAction()) &&
                r.executed()));

        // No temp/LDR results — blocked by vacancy
        assertFalse(results.stream().anyMatch(r -> "TEMPERATURE_FAN_CONTROL".equals(r.ruleName())));
        assertFalse(results.stream().anyMatch(r -> "LIGHT_INTENSITY_CONTROL".equals(r.ruleName())));
    }

    // ── Scenario 6: VACANT + hot temp → FAN stays OFF ─────────────────────────
    @Test
    @DisplayName("S6: Classroom VACANT + Temp 29°C → FAN stays OFF (PIR vacancy overrides temp)")
    void s6_vacantHotTemp_fanStaysOff() {
        driveToVacant();

        Instant t70 = BASE.plusSeconds(70);
        engine.setClock(Clock.fixed(t70, UTC));
        EnvironmentData hotData = makeData(29.0, 3000.0, false, t70);
        hotData.setFanStatus("OFF");
        hotData.setLightStatus("OFF");

        List<AutomationDecisionResult> results = engine.evaluateTelemetry(classroom, hotData);

        assertEquals("VACANT", engine.getOccupancyStatus(1L));
        assertFalse(results.stream().anyMatch(r -> "TEMPERATURE_FAN_CONTROL".equals(r.ruleName())),
                "Temperature policy must be blocked while VACANT");
    }

    // ── Scenario 7: VACANT + dark LDR → LIGHT stays OFF ──────────────────────
    @Test
    @DisplayName("S7: Classroom VACANT + LDR 1000 → LIGHT stays OFF (PIR vacancy overrides LDR)")
    void s7_vacantDarkLdr_lightStaysOff() {
        driveToVacant();

        Instant t70 = BASE.plusSeconds(70);
        engine.setClock(Clock.fixed(t70, UTC));
        EnvironmentData darkData = makeData(22.0, 1000.0, false, t70);
        darkData.setFanStatus("OFF");
        darkData.setLightStatus("OFF");

        List<AutomationDecisionResult> results = engine.evaluateTelemetry(classroom, darkData);

        assertEquals("VACANT", engine.getOccupancyStatus(1L));
        assertFalse(results.stream().anyMatch(r -> "LIGHT_INTENSITY_CONTROL".equals(r.ruleName())),
                "LDR light policy must be blocked while VACANT");
    }

    // ── Scenario 8: Motion after vacancy → OCCUPIED, normal automation restored ─
    @Test
    @DisplayName("S8: Motion detected after VACANT → OCCUPIED, normal temp/LDR automation restored")
    void s8_motionAfterVacancy_automationRestored() {
        driveToVacant();

        // Motion at T+80s (classroom occupied again)
        Instant t80 = BASE.plusSeconds(80);
        engine.setClock(Clock.fixed(t80, UTC));
        EnvironmentData reoccupied = makeData(29.0, 1000.0, true, t80);
        reoccupied.setFanStatus("OFF");
        reoccupied.setLightStatus("OFF");

        List<AutomationDecisionResult> results = engine.evaluateTelemetry(classroom, reoccupied);

        assertEquals("OCCUPIED", engine.getOccupancyStatus(1L));
        assertNull(engine.getVacancyCountdown(1L));

        // Normal automation should resume: fan ON (29°C > 24°C), light ON (1000 ≤ 1700)
        assertFan(results, AutomationDecisionStatus.EXECUTION_SUCCESS, "ON");
        assertLight(results, AutomationDecisionStatus.EXECUTION_SUCCESS, "ON");
    }

    // ── Scenario 9: Gas emergency → safety overrides everything ──────────────
    @Test
    @DisplayName("S9: Gas leak → EMERGENCY_PROTECTION, no relay commands issued")
    void s9_gasLeak_emergencyProtection() {
        EnvironmentData gasData = EnvironmentData.builder()
                .classroom(classroom).temperature(28.0)
                .fanStatus("OFF").lightStatus("OFF").lightLevel(1500.0)
                .motionDetected(true).gasLeak(true).gasPpm(520.0)
                .recordedAt(LocalDateTime.ofInstant(BASE, UTC)).build();

        List<AutomationDecisionResult> results = engine.evaluateTelemetry(classroom, gasData);

        assertTrue(results.stream().anyMatch(r ->
                "EMERGENCY_PROTECTION".equals(r.ruleName()) &&
                r.status() == AutomationDecisionStatus.ACTION_REQUIRED &&
                r.reason().contains("Relay switching disabled")));

        // No FAN or LIGHT commands
        assertFalse(results.stream().anyMatch(r -> "FAN".equals(r.targetDevice())));
        assertFalse(results.stream().anyMatch(r -> "LIGHT".equals(r.targetDevice())));
        verify(auditLogService).log(eq("SAFETY_EMERGENCY"), eq("Classroom"), eq(1L), contains("Gas leak detected"));
    }

    // ── Existing: Teacher override suppresses automation ─────────────────────
    @Test
    @DisplayName("Policy 5: Durable teacher override blocks automated fan control")
    void testDurableTeacherOverrideBlocksAutomation() {
        DeviceOverride fanOverride = DeviceOverride.builder()
                .classroom(classroom).deviceType("FAN").forcedState("OFF")
                .setBy("teacher@school.edu").reason("Exam in progress")
                .expiresAt(LocalDateTime.ofInstant(BASE.plusSeconds(3600), UTC))
                .active(true).build();

        when(deviceOverrideService.getActiveOverride(classroom, "FAN")).thenReturn(Optional.of(fanOverride));

        EnvironmentData hotData = EnvironmentData.builder()
                .classroom(classroom).temperature(30.0)
                .fanStatus("OFF").lightStatus("OFF").lightLevel(2000.0)
                .motionDetected(true)
                .recordedAt(LocalDateTime.ofInstant(BASE, UTC)).build();

        List<AutomationDecisionResult> results = engine.evaluateTelemetry(classroom, hotData);

        AutomationDecisionResult fanResult = results.stream()
                .filter(r -> "FAN".equals(r.targetDevice())).findFirst().orElseThrow();

        assertEquals(AutomationDecisionStatus.NO_ACTION_REQUIRED, fanResult.status());
        assertEquals("OFF", fanResult.requestedAction());
        assertFalse(fanResult.executed());
        assertTrue(fanResult.reason().contains("Durable teacher override active"));
    }

    // ── Existing: Stale telemetry skips automation ────────────────────────────
    @Test
    @DisplayName("Policy 3: Stale telemetry (>10 min old) returns INSUFFICIENT_DATA")
    void testStaleTelemetryReturnsInsufficientData() {
        LocalDateTime oldTime = LocalDateTime.ofInstant(BASE.minusSeconds(900), UTC);

        EnvironmentData staleData = EnvironmentData.builder()
                .classroom(classroom).temperature(30.0)
                .fanStatus("OFF").lightStatus("OFF").lightLevel(500.0)
                .motionDetected(true).recordedAt(oldTime).build();

        List<AutomationDecisionResult> results = engine.evaluateTelemetry(classroom, staleData);

        assertEquals(1, results.size());
        assertEquals(AutomationDecisionStatus.INSUFFICIENT_DATA, results.get(0).status());
        assertEquals("DATA_FRESHNESS_GATE", results.get(0).ruleName());
    }

    // ── Existing: Fan already in correct state → no command ───────────────────
    @Test
    @DisplayName("Fan already in desired state: NO_ACTION_REQUIRED")
    void testFanAlreadyInRequestedState() {
        EnvironmentData data = EnvironmentData.builder()
                .classroom(classroom).temperature(28.0)
                .fanStatus("ON").lightStatus("OFF").lightLevel(2000.0)
                .motionDetected(true)
                .recordedAt(LocalDateTime.ofInstant(BASE, UTC)).build();

        List<AutomationDecisionResult> results = engine.evaluateTelemetry(classroom, data);

        AutomationDecisionResult fanResult = results.stream()
                .filter(r -> "FAN".equals(r.targetDevice())).findFirst().orElseThrow();

        assertEquals(AutomationDecisionStatus.NO_ACTION_REQUIRED, fanResult.status());
        assertFalse(fanResult.executed());
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private EnvironmentData makeData(double temp, double ldr, boolean motion, Instant at) {
        return EnvironmentData.builder()
                .classroom(classroom).temperature(temp)
                .fanStatus("OFF").lightStatus("OFF").lightLevel(ldr)
                .motionDetected(motion)
                .recordedAt(LocalDateTime.ofInstant(at, UTC)).build();
    }

    /** Drives the engine to VACANT state (motion → no-motion → wait 65s). */
    private void driveToVacant() {
        // Establish OCCUPIED
        engine.evaluateTelemetry(classroom, makeData(29.0, 1000.0, true, BASE));
        // Start countdown
        Instant t1 = BASE.plusSeconds(1);
        engine.setClock(Clock.fixed(t1, UTC));
        engine.evaluateTelemetry(classroom, makeData(29.0, 1000.0, false, t1));
        // Expire countdown
        Instant t65 = BASE.plusSeconds(65);
        engine.setClock(Clock.fixed(t65, UTC));
        EnvironmentData d = makeData(29.0, 1000.0, false, t65);
        d.setFanStatus("ON");
        d.setLightStatus("ON");
        engine.evaluateTelemetry(classroom, d);
        assertEquals("VACANT", engine.getOccupancyStatus(1L));
    }

    private void assertOccupancy(List<AutomationDecisionResult> results, String expected) {
        assertTrue(results.stream().anyMatch(r ->
                "PIR_OCCUPANCY".equals(r.ruleName()) && expected.equals(r.requestedAction())),
                "Expected PIR_OCCUPANCY result with action=" + expected);
    }

    private void assertFan(List<AutomationDecisionResult> results,
                           AutomationDecisionStatus expectedStatus, String expectedAction) {
        AutomationDecisionResult fan = results.stream()
                .filter(r -> "FAN".equals(r.targetDevice())).findFirst().orElseThrow(
                        () -> new AssertionError("No FAN result found"));
        assertEquals(expectedStatus, fan.status());
        assertEquals(expectedAction, fan.requestedAction());
    }

    private void assertLight(List<AutomationDecisionResult> results,
                             AutomationDecisionStatus expectedStatus, String expectedAction) {
        AutomationDecisionResult light = results.stream()
                .filter(r -> "LIGHT".equals(r.targetDevice())).findFirst().orElseThrow(
                        () -> new AssertionError("No LIGHT result found"));
        assertEquals(expectedStatus, light.status());
        assertEquals(expectedAction, light.requestedAction());
    }
}
