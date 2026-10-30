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

    @Mock
    private DeviceRepository deviceRepository;

    @Mock
    private EnvironmentDataRepository environmentDataRepository;

    @Mock
    private DeviceCommandRepository deviceCommandRepository;

    @Mock
    private DeviceOverrideService deviceOverrideService;

    @Mock
    private AuditLogService auditLogService;

    private ClassroomAutomationEngine engine;
    private Classroom classroom;
    private List<Device> classroomDevices;

    private final Instant baseTime = Instant.parse("2026-10-26T12:00:00Z");
    private final ZoneId zoneId = ZoneId.of("UTC");

    @BeforeEach
    void setUp() {
        engine = new ClassroomAutomationEngine(
                deviceRepository,
                environmentDataRepository,
                deviceCommandRepository,
                deviceOverrideService,
                auditLogService,
                24.0,   // temp threshold per Stage 1 master spec
                1700.0, // light threshold per Stage 1 master spec
                180,    // 3 min cooldown
                300,    // 5 min inactivity
                600     // 10 min max freshness
        );
        engine.setClock(Clock.fixed(baseTime, zoneId));
        engine.resetTrackers();

        classroom = Classroom.builder()
                .id(1L)
                .roomNumber("A1")
                .active(true)
                .build();

        classroomDevices = new ArrayList<>();
        classroomDevices.add(Device.builder()
                .id(101L)
                .deviceId("FAN_RELAY_01")
                .deviceName("Classroom Fan")
                .deviceType(Device.DeviceType.RELAY)
                .classroom(classroom)
                .status(Device.DeviceStatus.OFFLINE)
                .build());
        classroomDevices.add(Device.builder()
                .id(102L)
                .deviceId("LIGHT_RELAY_01")
                .deviceName("Classroom Light")
                .deviceType(Device.DeviceType.RELAY)
                .classroom(classroom)
                .status(Device.DeviceStatus.OFFLINE)
                .build());

        lenient().when(deviceRepository.findByClassroom(classroom)).thenReturn(classroomDevices);
        lenient().when(environmentDataRepository.save(any(EnvironmentData.class))).thenAnswer(i -> i.getArgument(0));
        lenient().when(deviceCommandRepository.save(any(DeviceCommand.class))).thenAnswer(i -> i.getArgument(0));
        lenient().when(deviceOverrideService.getActiveOverride(any(), any())).thenReturn(Optional.empty());
    }

    @Test
    @DisplayName("Policy 1: Temperature > 24.0°C automatically turns Fan ON")
    void testTemperatureAbove24TurnsFanOn() {
        EnvironmentData data = EnvironmentData.builder()
                .classroom(classroom)
                .temperature(25.5)
                .fanStatus("OFF")
                .lightStatus("OFF")
                .lightLevel(2000.0)
                .motionDetected(true)
                .recordedAt(LocalDateTime.ofInstant(baseTime, zoneId))
                .build();

        List<AutomationDecisionResult> results = engine.evaluateTelemetry(classroom, data);

        AutomationDecisionResult fanResult = results.stream()
                .filter(r -> "FAN".equals(r.targetDevice()))
                .findFirst().orElseThrow();

        assertEquals(AutomationDecisionStatus.EXECUTION_SUCCESS, fanResult.status());
        assertEquals("ON", fanResult.requestedAction());
        assertEquals("ON", fanResult.newState());
        assertTrue(fanResult.executed());
        verify(deviceCommandRepository, atLeastOnce()).save(any(DeviceCommand.class));
        verify(auditLogService).log(eq("AUTO_FAN_CONTROL"), eq("Classroom"), eq(1L), contains("Temp=25.5"));
    }

    @Test
    @DisplayName("Policy 1: Temperature <= 24.0°C automatically turns Fan OFF")
    void testTemperatureBelowOrAt24TurnsFanOff() {
        EnvironmentData data = EnvironmentData.builder()
                .classroom(classroom)
                .temperature(24.0) // Exactly 24.0°C -> OFF per master rule
                .fanStatus("ON")
                .lightStatus("OFF")
                .lightLevel(2000.0)
                .motionDetected(true)
                .recordedAt(LocalDateTime.ofInstant(baseTime, zoneId))
                .build();

        List<AutomationDecisionResult> results = engine.evaluateTelemetry(classroom, data);

        AutomationDecisionResult fanResult = results.stream()
                .filter(r -> "FAN".equals(r.targetDevice()))
                .findFirst().orElseThrow();

        assertEquals(AutomationDecisionStatus.EXECUTION_SUCCESS, fanResult.status());
        assertEquals("OFF", fanResult.requestedAction());
        assertEquals("OFF", fanResult.newState());
        assertTrue(fanResult.executed());
    }

    @Test
    @DisplayName("Policy 1: Fan already in requested state requires no action")
    void testFanAlreadyInRequestedState() {
        EnvironmentData data = EnvironmentData.builder()
                .classroom(classroom)
                .temperature(28.0)
                .fanStatus("ON") // Already ON
                .lightStatus("OFF")
                .lightLevel(2000.0)
                .motionDetected(true)
                .recordedAt(LocalDateTime.ofInstant(baseTime, zoneId))
                .build();

        List<AutomationDecisionResult> results = engine.evaluateTelemetry(classroom, data);

        AutomationDecisionResult fanResult = results.stream()
                .filter(r -> "FAN".equals(r.targetDevice()))
                .findFirst().orElseThrow();

        assertEquals(AutomationDecisionStatus.NO_ACTION_REQUIRED, fanResult.status());
        assertEquals("ON", fanResult.requestedAction());
        assertFalse(fanResult.executed());
    }

    @Test
    @DisplayName("Policy 2: Light intensity <= 1700 turns Light ON")
    void testLightIntensityBelowOrAt1700TurnsLightOn() {
        EnvironmentData data = EnvironmentData.builder()
                .classroom(classroom)
                .temperature(22.0)
                .fanStatus("OFF")
                .lightStatus("OFF")
                .lightLevel(1650.0) // <= 1700 -> ON
                .motionDetected(true)
                .recordedAt(LocalDateTime.ofInstant(baseTime, zoneId))
                .build();

        List<AutomationDecisionResult> results = engine.evaluateTelemetry(classroom, data);

        AutomationDecisionResult lightResult = results.stream()
                .filter(r -> "LIGHT".equals(r.targetDevice()))
                .findFirst().orElseThrow();

        assertEquals(AutomationDecisionStatus.EXECUTION_SUCCESS, lightResult.status());
        assertEquals("ON", lightResult.requestedAction());
        assertEquals("ON", lightResult.newState());
        assertTrue(lightResult.executed());
    }

    @Test
    @DisplayName("Policy 2: Light intensity > 1700 turns Light OFF")
    void testLightIntensityAbove1700TurnsLightOff() {
        EnvironmentData data = EnvironmentData.builder()
                .classroom(classroom)
                .temperature(22.0)
                .fanStatus("OFF")
                .lightStatus("ON")
                .lightLevel(1850.0) // > 1700 -> OFF
                .motionDetected(true)
                .recordedAt(LocalDateTime.ofInstant(baseTime, zoneId))
                .build();

        List<AutomationDecisionResult> results = engine.evaluateTelemetry(classroom, data);

        AutomationDecisionResult lightResult = results.stream()
                .filter(r -> "LIGHT".equals(r.targetDevice()))
                .findFirst().orElseThrow();

        assertEquals(AutomationDecisionStatus.EXECUTION_SUCCESS, lightResult.status());
        assertEquals("OFF", lightResult.requestedAction());
        assertEquals("OFF", lightResult.newState());
        assertTrue(lightResult.executed());
    }

    @Test
    @DisplayName("Policy 5: Durable Teacher Override blocks automated sensor changes")
    void testDurableTeacherOverrideBlocksAutomation() {
        // Teacher has manually forced FAN to OFF
        DeviceOverride fanOverride = DeviceOverride.builder()
                .classroom(classroom)
                .deviceType("FAN")
                .forcedState("OFF")
                .setBy("teacher@school.edu")
                .reason("Exam in progress")
                .expiresAt(LocalDateTime.ofInstant(baseTime.plusSeconds(3600), zoneId))
                .active(true)
                .build();

        when(deviceOverrideService.getActiveOverride(classroom, "FAN")).thenReturn(Optional.of(fanOverride));

        // Ingest telemetry with hot temperature (30.0°C) which would normally trigger Fan ON
        EnvironmentData hotData = EnvironmentData.builder()
                .classroom(classroom)
                .temperature(30.0)
                .fanStatus("OFF")
                .lightStatus("OFF")
                .lightLevel(2000.0)
                .motionDetected(true)
                .recordedAt(LocalDateTime.ofInstant(baseTime, zoneId))
                .build();

        List<AutomationDecisionResult> results = engine.evaluateTelemetry(classroom, hotData);

        AutomationDecisionResult fanResult = results.stream()
                .filter(r -> "FAN".equals(r.targetDevice()))
                .findFirst().orElseThrow();

        // Must remain in forced OFF state without automated change
        assertEquals(AutomationDecisionStatus.NO_ACTION_REQUIRED, fanResult.status());
        assertEquals("OFF", fanResult.requestedAction());
        assertFalse(fanResult.executed());
        assertTrue(fanResult.reason().contains("Durable teacher override active"));
    }

    @Test
    @DisplayName("Policy 4: Anti-Flapping Cooldown (180s) prevents rapid toggling")
    void testCooldownPreventsRapidSwitching() {
        // Step 1: Fan switched ON at T=0
        EnvironmentData data1 = EnvironmentData.builder()
                .classroom(classroom)
                .temperature(27.0)
                .fanStatus("OFF")
                .lightStatus("OFF")
                .lightLevel(2000.0)
                .motionDetected(true)
                .recordedAt(LocalDateTime.ofInstant(baseTime, zoneId))
                .build();

        List<AutomationDecisionResult> results1 = engine.evaluateTelemetry(classroom, data1);
        AutomationDecisionResult fan1 = results1.stream().filter(r -> "FAN".equals(r.targetDevice())).findFirst().orElseThrow();
        assertEquals(AutomationDecisionStatus.EXECUTION_SUCCESS, fan1.status());

        // Step 2: At T=60 seconds, temperature drops to 22.0°C
        Instant t60 = baseTime.plusSeconds(60);
        engine.setClock(Clock.fixed(t60, zoneId));

        EnvironmentData data2 = EnvironmentData.builder()
                .classroom(classroom)
                .temperature(22.0)
                .fanStatus("ON")
                .lightStatus("OFF")
                .lightLevel(2000.0)
                .motionDetected(true)
                .recordedAt(LocalDateTime.ofInstant(t60, zoneId))
                .build();

        List<AutomationDecisionResult> results2 = engine.evaluateTelemetry(classroom, data2);
        AutomationDecisionResult fan2 = results2.stream().filter(r -> "FAN".equals(r.targetDevice())).findFirst().orElseThrow();

        assertEquals(AutomationDecisionStatus.COOLDOWN_ACTIVE, fan2.status());
        assertFalse(fan2.executed());
        assertTrue(fan2.reason().contains("Cooldown active"));
    }

    @Test
    @DisplayName("Policy 3: Stale telemetry data (>10 min old) returns INSUFFICIENT_DATA")
    void testStaleTelemetryReturnsInsufficientData() {
        LocalDateTime oldTime = LocalDateTime.ofInstant(baseTime.minusSeconds(900), zoneId);

        EnvironmentData staleData = EnvironmentData.builder()
                .classroom(classroom)
                .temperature(30.0)
                .fanStatus("OFF")
                .lightStatus("OFF")
                .lightLevel(500.0)
                .motionDetected(true)
                .recordedAt(oldTime)
                .build();

        List<AutomationDecisionResult> results = engine.evaluateTelemetry(classroom, staleData);

        assertEquals(1, results.size());
        AutomationDecisionResult freshness = results.get(0);
        assertEquals(AutomationDecisionStatus.INSUFFICIENT_DATA, freshness.status());
        assertEquals("DATA_FRESHNESS_GATE", freshness.ruleName());
        assertFalse(freshness.executed());
    }

    @Test
    @DisplayName("Critical Safety: Gas leak triggers EMERGENCY_PROTECTION and blocks relay arcing")
    void testGasLeakTriggersEmergency() {
        EnvironmentData gasData = EnvironmentData.builder()
                .classroom(classroom)
                .temperature(28.0) // Would otherwise trigger fan
                .fanStatus("OFF")
                .lightStatus("OFF")
                .lightLevel(1500.0)
                .motionDetected(true)
                .gasLeak(true)
                .gasPpm(520.0)
                .recordedAt(LocalDateTime.ofInstant(baseTime, zoneId))
                .build();

        List<AutomationDecisionResult> results = engine.evaluateTelemetry(classroom, gasData);

        assertTrue(results.stream().anyMatch(r ->
                "EMERGENCY_PROTECTION".equals(r.ruleName()) &&
                r.status() == AutomationDecisionStatus.ACTION_REQUIRED &&
                r.reason().contains("Relay switching disabled")
        ));
        // Verify no fan or light relay commands were issued
        assertFalse(results.stream().anyMatch(r -> "FAN".equals(r.targetDevice())));
        verify(auditLogService).log(eq("SAFETY_EMERGENCY"), eq("Classroom"), eq(1L), contains("Gas leak detected"));
    }
}
