package com.finalYear.smartClassRoom.ai.tools;

import com.finalYear.smartClassRoom.ai.agent.DeviceAgent;
import com.finalYear.smartClassRoom.ai.agent.TimetableAgent;
import com.finalYear.smartClassRoom.ai.model.AgentRequest;
import com.finalYear.smartClassRoom.ai.model.AgentResponse;
import com.finalYear.smartClassRoom.entity.*;
import com.finalYear.smartClassRoom.repository.*;
import com.finalYear.smartClassRoom.service.AuditLogService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class ClassroomToolsStage4Test {

    @Mock
    private EnvironmentDataRepository environmentDataRepository;

    @Mock
    private DeviceRepository deviceRepository;

    @Mock
    private ClassroomRepository classroomRepository;

    @Mock
    private TimetableRepository timetableRepository;

    @Mock
    private AttendanceSessionRepository attendanceSessionRepository;

    @Mock
    private AuditLogService auditLogService;

    @Mock
    private UserRepository userRepository;

    private ClassroomToolsService toolsService;
    private ClassroomToolDefinitions toolDefinitions;
    private ClassroomToolDispatcher toolDispatcher;
    private DeviceAgent deviceAgent;
    private TimetableAgent timetableAgent;

    private Classroom classroom101;
    private User teacherUser;
    private User studentUser;

    @BeforeEach
    void setUp() {
        toolsService = new ClassroomToolsService(
                environmentDataRepository,
                deviceRepository,
                classroomRepository,
                timetableRepository,
                attendanceSessionRepository,
                auditLogService,
                userRepository
        );

        toolDefinitions = new ClassroomToolDefinitions();
        toolDispatcher = new ClassroomToolDispatcher(toolsService, toolDefinitions);
        deviceAgent = new DeviceAgent(toolsService, userRepository);
        timetableAgent = new TimetableAgent(toolsService);

        classroom101 = Classroom.builder()
                .id(1L)
                .roomNumber("LH-101")
                .active(true)
                .build();

        teacherUser = User.builder()
                .id(10L)
                .email("prof.sharma@smartclass.edu")
                .firstName("Prof")
                .lastName("Sharma")
                .role(User.Role.TEACHER)
                .build();

        studentUser = User.builder()
                .id(20L)
                .email("student1@smartclass.edu")
                .firstName("Alice")
                .lastName("Smith")
                .role(User.Role.STUDENT)
                .build();
    }

    // ── 1. getCurrentSensorReadings Tests ─────────────────────────────────────
    @Test
    @DisplayName("getCurrentSensorReadings returns live telemetry when data exists")
    void testGetCurrentSensorReadings_Success() {
        when(classroomRepository.findById(1L)).thenReturn(Optional.of(classroom101));

        EnvironmentData data = EnvironmentData.builder()
                .classroom(classroom101)
                .temperature(24.5)
                .humidity(55.0)
                .lightLevel(420.0)
                .gasLeak(false)
                .motionDetected(true)
                .roomState("OCCUPIED")
                .fanStatus("ON")
                .lightStatus("ON")
                .recordedAt(LocalDateTime.now())
                .build();

        when(environmentDataRepository.findTopByClassroomOrderByRecordedAtDesc(classroom101)).thenReturn(data);

        Map<String, Object> readings = toolsService.getCurrentSensorReadings(1L);

        assertEquals("AVAILABLE", readings.get("status"));
        assertEquals("LH-101", readings.get("roomNumber"));
        assertEquals(24.5, readings.get("temperatureC"));
        assertEquals(55.0, readings.get("humidityPercent"));
        assertEquals(420.0, readings.get("lightLevel"));
        assertEquals(false, readings.get("gasLeakDetected"));
        assertEquals(true, readings.get("motionDetected"));
        assertEquals("OCCUPIED", readings.get("roomOccupancyState"));
        assertEquals("ON", readings.get("fanStatus"));
        assertEquals("ON", readings.get("lightStatus"));
    }

    @Test
    @DisplayName("getCurrentSensorReadings returns UNAVAILABLE when classroom has no telemetry yet")
    void testGetCurrentSensorReadings_NoData() {
        when(classroomRepository.findById(1L)).thenReturn(Optional.of(classroom101));
        when(environmentDataRepository.findTopByClassroomOrderByRecordedAtDesc(classroom101)).thenReturn(null);

        Map<String, Object> readings = toolsService.getCurrentSensorReadings(1L);

        assertEquals("UNAVAILABLE", readings.get("status"));
        assertTrue(((String) readings.get("message")).contains("No telemetry data recorded yet"));
    }

    @Test
    @DisplayName("getCurrentSensorReadings returns ERROR when classroom not found")
    void testGetCurrentSensorReadings_ClassNotFound() {
        when(classroomRepository.findById(99L)).thenReturn(Optional.empty());

        Map<String, Object> readings = toolsService.getCurrentSensorReadings(99L);

        assertEquals("ERROR", readings.get("status"));
    }

    // ── 2. getDeviceStatus Tests ──────────────────────────────────────────────
    @Test
    @DisplayName("getDeviceStatus by specific device ID returns status")
    void testGetDeviceStatus_ByDeviceId() {
        Device dev = Device.builder()
                .deviceId("ESP32_01")
                .deviceName("Room 101 Controller")
                .deviceType(Device.DeviceType.ESP32)
                .status(Device.DeviceStatus.ONLINE)
                .classroom(classroom101)
                .build();

        when(deviceRepository.findByDeviceId("ESP32_01")).thenReturn(Optional.of(dev));

        Map<String, Object> res = toolsService.getDeviceStatus(1L, "ESP32_01");

        assertEquals("SUCCESS", res.get("status"));
        assertEquals("ESP32_01", res.get("deviceId"));
        assertEquals("ONLINE", res.get("onlineStatus"));
        assertEquals("LH-101", res.get("roomNumber"));
    }

    @Test
    @DisplayName("getDeviceStatus by classroom returns online/offline counts and device list")
    void testGetDeviceStatus_ByClassroom() {
        when(classroomRepository.findById(1L)).thenReturn(Optional.of(classroom101));

        Device d1 = Device.builder().deviceId("D1").deviceName("Light 1").deviceType(Device.DeviceType.RELAY).status(Device.DeviceStatus.ONLINE).build();
        Device d2 = Device.builder().deviceId("D2").deviceName("Projector").deviceType(Device.DeviceType.PROJECTOR).status(Device.DeviceStatus.OFFLINE).build();

        when(deviceRepository.findByClassroom(classroom101)).thenReturn(List.of(d1, d2));

        Map<String, Object> res = toolsService.getDeviceStatus(1L, null);

        assertEquals("SUCCESS", res.get("status"));
        assertEquals(2, res.get("totalDevices"));
        assertEquals(1L, res.get("onlineCount"));
        assertEquals(1L, res.get("offlineCount"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> list = (List<Map<String, Object>>) res.get("devices");
        assertEquals(2, list.size());
    }

    // ── 3. getCurrentTimetable & Upcoming Classes Tests ───────────────────────
    @Test
    @DisplayName("getCurrentTimetable returns IN_SESSION when current time matches a slot")
    void testGetCurrentTimetable_InSession() {
        when(classroomRepository.findById(1L)).thenReturn(Optional.of(classroom101));

        LocalDate monday = LocalDate.of(2026, 10, 26); // Monday
        LocalTime now = LocalTime.of(10, 30);
        LocalTime start = LocalTime.of(10, 0);
        LocalTime end = LocalTime.of(11, 0);

        Subject sub = Subject.builder().id(5L).name("Operating Systems").build();
        User facultyUser = User.builder().firstName("John").lastName("Doe").build();
        Teacher teacher = Teacher.builder().id(2L).user(facultyUser).build();

        Timetable activeSlot = Timetable.builder()
                .id(100L)
                .classroom(classroom101)
                .subject(sub)
                .teacher(teacher)
                .startTime(start)
                .endTime(end)
                .active(true)
                .dayOfWeek(Timetable.DayOfWeek.MONDAY)
                .build();

        when(timetableRepository.findByClassroomAndDayOfWeek(eq(classroom101), eq(Timetable.DayOfWeek.MONDAY)))
                .thenReturn(List.of(activeSlot));

        Map<String, Object> tt = toolsService.getCurrentTimetable(1L, monday, now);

        assertEquals("IN_SESSION", tt.get("status"));
        assertEquals("Operating Systems", tt.get("subject"));
        assertEquals("John Doe", tt.get("teacher"));
    }

    @Test
    @DisplayName("getCurrentTimetable returns NO_ACTIVE_CLASS on Sunday per domain rule")
    void testGetCurrentTimetable_Sunday() {
        when(classroomRepository.findById(1L)).thenReturn(Optional.of(classroom101));

        LocalDate sunday = LocalDate.of(2026, 10, 25); // Sunday
        LocalTime now = LocalTime.of(10, 30);

        Map<String, Object> tt = toolsService.getCurrentTimetable(1L, sunday, now);

        assertEquals("NO_ACTIVE_CLASS", tt.get("status"));
        assertEquals("SUNDAY", tt.get("dayOfWeek"));
    }

    @Test
    @DisplayName("getUpcomingClasses returns future classes for today")
    void testGetUpcomingClasses() {
        when(classroomRepository.findById(1L)).thenReturn(Optional.of(classroom101));

        LocalDate monday = LocalDate.of(2026, 10, 26); // Monday
        LocalTime now = LocalTime.of(9, 0);
        Subject sub = Subject.builder().name("Compiler Design").build();
        User facultyUser = User.builder().firstName("Ada").lastName("Lovelace").build();
        Teacher teacher = Teacher.builder().user(facultyUser).build();

        Timetable futureSlot = Timetable.builder()
                .classroom(classroom101)
                .subject(sub)
                .teacher(teacher)
                .startTime(LocalTime.of(10, 0))
                .endTime(LocalTime.of(11, 0))
                .active(true)
                .dayOfWeek(Timetable.DayOfWeek.MONDAY)
                .build();

        when(timetableRepository.findByClassroomAndDayOfWeek(eq(classroom101), eq(Timetable.DayOfWeek.MONDAY)))
                .thenReturn(List.of(futureSlot));

        Map<String, Object> upcoming = toolsService.getUpcomingClasses(1L, 5, monday, now);

        assertEquals("SUCCESS", upcoming.get("status"));
        assertEquals(1, upcoming.get("upcomingCount"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> classes = (List<Map<String, Object>>) upcoming.get("classes");
        assertEquals("Compiler Design", classes.get(0).get("subject"));
    }

    // ── 4. Free-Tier Privacy Rule: getAttendanceSummary ───────────────────────
    @Test
    @DisplayName("getAttendanceSummary returns strictly aggregate data and NO student PII")
    void testGetAttendanceSummary_PrivacyRuleEnforced() {
        when(classroomRepository.findById(1L)).thenReturn(Optional.of(classroom101));

        AttendanceSession s1 = AttendanceSession.builder()
                .id(1L)
                .classroom(classroom101)
                .sessionDate(LocalDate.now())
                .totalStudents(40)
                .presentCount(35)
                .build();

        AttendanceSession s2 = AttendanceSession.builder()
                .id(2L)
                .classroom(classroom101)
                .sessionDate(LocalDate.now())
                .totalStudents(40)
                .presentCount(37)
                .build();

        when(attendanceSessionRepository.findByClassroom(classroom101)).thenReturn(List.of(s1, s2));

        Map<String, Object> summary = toolsService.getAttendanceSummary(1L, null);

        assertEquals("SUCCESS", summary.get("status"));
        assertEquals(2, summary.get("totalSessions"));
        assertEquals(80, summary.get("totalStudentSlots"));
        assertEquals(72, summary.get("totalPresent"));
        assertEquals(8, summary.get("totalAbsent"));
        assertEquals("90.0%", summary.get("attendancePercentage"));
        assertEquals("OPTIMAL", summary.get("attendanceHealth"));

        // Verify Privacy Rule: NO student-specific names, roll numbers, or IDs
        assertFalse(summary.containsKey("students"));
        assertFalse(summary.containsKey("studentNames"));
        assertFalse(summary.containsKey("rollNumbers"));
        assertFalse(summary.containsKey("studentIds"));
        for (Object val : summary.values()) {
            assertFalse(val.toString().toLowerCase().contains("alice"));
            assertFalse(val.toString().toLowerCase().contains("smith"));
        }
    }

    // ── 5. Control Tools: RBAC Enforcement ────────────────────────────────────
    @Test
    @DisplayName("executeDeviceControl rejects STUDENT attempts with explicit permission message")
    void testExecuteDeviceControl_StudentForbidden() {
        Map<String, Object> result = toolsService.executeDeviceControl(
                1L, "LIGHT", "OFF", false, studentUser
        );

        assertEquals("FORBIDDEN", result.get("status"));
        assertEquals(false, result.get("executed"));
        assertTrue(((String) result.get("message")).contains("You don't have permission to control classroom devices"));

        // Verify no audit log of success and no device changes
        verify(deviceRepository, never()).save(any());
        verify(auditLogService, never()).log(anyString(), anyString(), anyLong(), anyString());
    }

    // ── 6. Control Tools: Confirmation & Safety Checks ────────────────────────
    @Test
    @DisplayName("executeDeviceControl with confirmed=false returns preview message and checks safety")
    void testExecuteDeviceControl_ConfirmationRequiredWithSafetyChecks() {
        when(classroomRepository.findById(1L)).thenReturn(Optional.of(classroom101));

        // Setup recent motion detected in classroom within 5 minutes
        EnvironmentData recentData = EnvironmentData.builder()
                .classroom(classroom101)
                .motionDetected(true)
                .roomState("ACTIVE")
                .recordedAt(LocalDateTime.now().minusMinutes(2))
                .build();
        when(environmentDataRepository.findTopByClassroomOrderByRecordedAtDesc(classroom101)).thenReturn(recentData);

        Map<String, Object> preview = toolsService.executeDeviceControl(
                1L, "LIGHT", "OFF", false, teacherUser
        );

        assertEquals("CONFIRMATION_REQUIRED", preview.get("status"));
        assertEquals(true, preview.get("requiresConfirmation"));
        assertTrue(((String) preview.get("previewMessage")).contains("Are you sure you want to turn OFF LIGHT in LH-101?"));

        @SuppressWarnings("unchecked")
        List<String> warnings = (List<String>) preview.get("safetyWarnings");
        assertNotNull(warnings);
        assertFalse(warnings.isEmpty());
        assertTrue(warnings.get(0).contains("Motion was detected in the classroom within the last 5 minutes"));

        // No execution occurred yet
        verify(deviceRepository, never()).save(any());
    }

    @Test
    @DisplayName("executeDeviceControl with confirmed=true executes change and writes audit log")
    void testExecuteDeviceControl_ConfirmedExecutionAndAuditLog() {
        when(classroomRepository.findById(1L)).thenReturn(Optional.of(classroom101));

        Device projector = Device.builder()
                .deviceId("PROJ_01")
                .deviceName("Main Projector")
                .deviceType(Device.DeviceType.PROJECTOR)
                .status(Device.DeviceStatus.ONLINE)
                .classroom(classroom101)
                .build();

        when(deviceRepository.findByClassroom(classroom101)).thenReturn(List.of(projector));
        when(environmentDataRepository.findTopByClassroomOrderByRecordedAtDesc(classroom101)).thenReturn(null);

        Map<String, Object> success = toolsService.executeDeviceControl(
                1L, "PROJECTOR", "OFF", true, teacherUser
        );

        assertEquals("SUCCESS", success.get("status"));
        assertEquals(true, success.get("executed"));
        assertEquals("OFF", success.get("newState"));
        assertEquals(Device.DeviceStatus.OFFLINE, projector.getStatus());

        // Verify repository saved the updated device
        verify(deviceRepository).save(projector);

        // Verify Audit Log was recorded with user and state transition
        ArgumentCaptor<String> detailsCaptor = ArgumentCaptor.forClass(String.class);
        verify(auditLogService).log(eq("AI_DEVICE_CONTROL"), eq("Classroom"), eq(1L), detailsCaptor.capture());
        String loggedDetails = detailsCaptor.getValue();
        assertTrue(loggedDetails.contains("prof.sharma@smartclass.edu"));
        assertTrue(loggedDetails.contains("TEACHER"));
        assertTrue(loggedDetails.contains("PROJECTOR to OFF"));
    }

    // ── 7. Tool Dispatcher & Definitions Tests ───────────────────────────────
    @Test
    @DisplayName("ClassroomToolDispatcher dispatches tools by name and arguments")
    void testToolDispatcher_Dispatch() {
        when(classroomRepository.findById(1L)).thenReturn(Optional.of(classroom101));
        when(environmentDataRepository.findTopByClassroomOrderByRecordedAtDesc(classroom101)).thenReturn(null);

        Map<String, Object> res = toolDispatcher.dispatch(
                "getCurrentSensorReadings", Map.of("classroomId", 1L), teacherUser
        );

        assertEquals("UNAVAILABLE", res.get("status"));

        // Unknown tool returns error
        Map<String, Object> unknown = toolDispatcher.dispatch("nonExistentTool", Map.of(), teacherUser);
        assertEquals("ERROR", unknown.get("status"));
        assertTrue(((String) unknown.get("message")).contains("Unknown tool name"));
    }

    // ── 8. DeviceAgent & TimetableAgent Integration Tests ────────────────────
    @Test
    @DisplayName("DeviceAgent rejects student control intent with permission error")
    void testDeviceAgent_StudentControlRejected() {
        AgentRequest request = AgentRequest.builder()
                .prompt("Turn off the lights in room 101")
                .role(User.Role.STUDENT)
                .classroomId(1L)
                .build();

        AgentResponse response = deviceAgent.handle(request);

        assertNotNull(response);
        assertTrue(response.getAnswer().contains("You don't have permission to control classroom devices"));
    }

    @Test
    @DisplayName("DeviceAgent returns preview and prompt for confirmation on unconfirmed staff request")
    void testDeviceAgent_StaffControlConfirmationPreview() {
        when(classroomRepository.findById(1L)).thenReturn(Optional.of(classroom101));
        when(userRepository.findById(10L)).thenReturn(Optional.of(teacherUser));

        AgentRequest request = AgentRequest.builder()
                .userId(10L)
                .role(User.Role.TEACHER)
                .prompt("Turn off the projector")
                .classroomId(1L)
                .build();

        AgentResponse response = deviceAgent.handle(request);

        assertNotNull(response);
        assertTrue(response.getAnswer().contains("Are you sure you want to turn OFF PROJECTOR"));
        assertTrue(response.getAnswer().contains("Please reply with 'Confirm' to execute"));
    }

    private Timetable.DayOfWeek mapCurrentDayOfWeek() {
        return switch (LocalDate.now().getDayOfWeek()) {
            case MONDAY -> Timetable.DayOfWeek.MONDAY;
            case TUESDAY -> Timetable.DayOfWeek.TUESDAY;
            case WEDNESDAY -> Timetable.DayOfWeek.WEDNESDAY;
            case THURSDAY -> Timetable.DayOfWeek.THURSDAY;
            case FRIDAY -> Timetable.DayOfWeek.FRIDAY;
            case SATURDAY -> Timetable.DayOfWeek.SATURDAY;
            case SUNDAY -> Timetable.DayOfWeek.MONDAY;
        };
    }
}
