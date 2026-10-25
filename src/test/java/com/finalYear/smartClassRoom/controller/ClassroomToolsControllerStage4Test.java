package com.finalYear.smartClassRoom.controller;

import com.finalYear.smartClassRoom.ai.tools.ClassroomToolDispatcher;
import com.finalYear.smartClassRoom.ai.tools.ClassroomToolsService;
import com.finalYear.smartClassRoom.config.JwtService;
import com.finalYear.smartClassRoom.entity.User;
import com.finalYear.smartClassRoom.repository.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ClassroomToolsController.class)
@AutoConfigureMockMvc(addFilters = false)
public class ClassroomToolsControllerStage4Test {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ClassroomToolsService toolsService;

    @MockBean
    private ClassroomToolDispatcher dispatcher;

    @MockBean
    private UserRepository userRepository;

    @MockBean
    private JwtService jwtService;

    @Test
    @DisplayName("GET /api/ai/tools/definitions should return tool definitions")
    void testGetDefinitions() throws Exception {
        when(dispatcher.getToolDefinitions()).thenReturn(List.of(
                Map.of("name", "getCurrentSensorReadings"),
                Map.of("name", "controlDevice")
        ));

        mockMvc.perform(get("/api/ai/tools/definitions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].name").value("getCurrentSensorReadings"));
    }

    @Test
    @DisplayName("GET /api/ai/tools/sensors should return sensor telemetry")
    void testGetSensors() throws Exception {
        when(toolsService.getCurrentSensorReadings(1L)).thenReturn(Map.of(
                "status", "AVAILABLE",
                "roomNumber", "LH-101",
                "temperatureC", 25.0
        ));

        mockMvc.perform(get("/api/ai/tools/sensors?classroomId=1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("AVAILABLE"))
                .andExpect(jsonPath("$.roomNumber").value("LH-101"))
                .andExpect(jsonPath("$.temperatureC").value(25.0));
    }

    @Test
    @DisplayName("GET /api/ai/tools/attendance-summary should return aggregate summary")
    void testGetAttendanceSummary() throws Exception {
        when(toolsService.getAttendanceSummary(1L, null)).thenReturn(Map.of(
                "status", "SUCCESS",
                "totalSessions", 5,
                "attendancePercentage", "92.5%"
        ));

        mockMvc.perform(get("/api/ai/tools/attendance-summary?classroomId=1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.totalSessions").value(5))
                .andExpect(jsonPath("$.attendancePercentage").value("92.5%"));
    }

    @Test
    @DisplayName("POST /api/ai/tools/control should return 403 when forbidden")
    void testControlDevice_Forbidden() throws Exception {
        when(toolsService.executeDeviceControl(any(), any(), any(), anyBoolean(), any()))
                .thenReturn(Map.of(
                        "status", "FORBIDDEN",
                        "message", "You don't have permission to control classroom devices. Only teachers and administrators can perform this action."
                ));

        mockMvc.perform(post("/api/ai/tools/control")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"classroomId\": 1, \"target\": \"LIGHT\", \"action\": \"OFF\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value("FORBIDDEN"))
                .andExpect(jsonPath("$.message").value("You don't have permission to control classroom devices. Only teachers and administrators can perform this action."));
    }

    @Test
    @DisplayName("POST /api/ai/tools/control should return 200 with preview when confirmation required")
    void testControlDevice_ConfirmationRequired() throws Exception {
        when(toolsService.executeDeviceControl(any(), any(), any(), anyBoolean(), any()))
                .thenReturn(Map.of(
                        "status", "CONFIRMATION_REQUIRED",
                        "previewMessage", "Are you sure you want to turn OFF LIGHT in LH-101?",
                        "requiresConfirmation", true
                ));

        mockMvc.perform(post("/api/ai/tools/control")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"classroomId\": 1, \"target\": \"LIGHT\", \"action\": \"OFF\", \"confirmed\": false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMATION_REQUIRED"))
                .andExpect(jsonPath("$.requiresConfirmation").value(true))
                .andExpect(jsonPath("$.previewMessage").value("Are you sure you want to turn OFF LIGHT in LH-101?"));
    }
}
