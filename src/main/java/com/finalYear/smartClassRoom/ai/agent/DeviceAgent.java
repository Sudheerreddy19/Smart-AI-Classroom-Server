package com.finalYear.smartClassRoom.ai.agent;

import com.finalYear.smartClassRoom.ai.model.AgentRequest;
import com.finalYear.smartClassRoom.ai.model.AgentResponse;
import com.finalYear.smartClassRoom.ai.tools.ClassroomToolsService;
import com.finalYear.smartClassRoom.entity.User;
import com.finalYear.smartClassRoom.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Map;

@Component
@RequiredArgsConstructor
@Slf4j
public class DeviceAgent extends BaseAgent {

    private final ClassroomToolsService toolsService;
    private final UserRepository userRepository;

    @Override
    public AgentResponse handle(AgentRequest request) {
        String prompt = request.getPrompt().toLowerCase(Locale.ROOT);
        User.Role role = request.getRole();
        Long classroomId = request.getClassroomId();

        // 1. Control Action Intent (turn on/off, switch on/off, power)
        if (isControlIntent(prompt)) {
            // RBAC Check for Students
            if (role == User.Role.STUDENT) {
                return AgentResponse.builder()
                        .answer("You don't have permission to control classroom devices. Only teachers and administrators can perform this action.")
                        .agentName(agentName())
                        .build();
            }

            User user = null;
            if (request.getUserId() != null) {
                user = userRepository.findById(request.getUserId()).orElse(null);
            }
            if (user == null) {
                user = User.builder().email("staff@smartclass.edu").role(role != null ? role : User.Role.TEACHER).build();
            }

            String target = extractTarget(prompt);
            String action = prompt.contains("on") ? "ON" : "OFF";
            boolean isConfirmed = prompt.contains("confirm") || prompt.contains("proceed") || prompt.contains("yes");

            Map<String, Object> result = toolsService.executeDeviceControl(classroomId, target, action, isConfirmed, user);

            if ("CONFIRMATION_REQUIRED".equals(result.get("status"))) {
                @SuppressWarnings("unchecked")
                List<String> warnings = (List<String>) result.get("safetyWarnings");
                StringBuilder sb = new StringBuilder();
                sb.append(result.get("previewMessage")).append("\n\n");
                if (warnings != null && !warnings.isEmpty()) {
                    for (String w : warnings) {
                        sb.append("⚠️ ").append(w).append("\n");
                    }
                    sb.append("\n");
                }
                sb.append("Please reply with 'Confirm' to execute this action.");

                return AgentResponse.builder()
                        .answer(sb.toString())
                        .agentName(agentName())
                        .data(result)
                        .followUpQuestions(List.of("Confirm " + action + " " + target, "Cancel command"))
                        .build();
            }

            if ("SUCCESS".equals(result.get("status"))) {
                return AgentResponse.builder()
                        .answer((String) result.get("message"))
                        .agentName(agentName())
                        .data(result)
                        .build();
            }

            return AgentResponse.builder()
                    .answer((String) result.get("message"))
                    .agentName(agentName())
                    .data(result)
                    .build();
        }

        // 2. Sensor / Telemetry Query Intent (temperature, humidity, motion, occupancy, gas)
        if (isSensorQuery(prompt)) {
            Map<String, Object> sensors = toolsService.getCurrentSensorReadings(classroomId);
            if ("AVAILABLE".equals(sensors.get("status"))) {
                String telemetryText = String.format(
                        "Live classroom telemetry for %s:\n" +
                                "• Temperature: %.1f°C\n" +
                                "• Humidity: %.1f%%\n" +
                                "• Light Level: %.1f lux (Lights: %s)\n" +
                                "• Fans: %s\n" +
                                "• Room Status: %s (Motion: %s)\n" +
                                "• Gas Safety: %s",
                        sensors.get("roomNumber"),
                        sensors.get("temperatureC"),
                        sensors.get("humidityPercent"),
                        sensors.get("lightLevel"),
                        sensors.get("lightStatus"),
                        sensors.get("fanStatus"),
                        sensors.get("roomOccupancyState"),
                        Boolean.TRUE.equals(sensors.get("motionDetected")) ? "Detected" : "None",
                        Boolean.TRUE.equals(sensors.get("gasLeakDetected")) ? "ALERT - Gas Leak Detected!" : "Normal"
                );

                return AgentResponse.builder()
                        .answer(telemetryText)
                        .agentName(agentName())
                        .data(sensors)
                        .build();
            } else {
                return AgentResponse.builder()
                        .answer((String) sensors.get("message"))
                        .agentName(agentName())
                        .data(sensors)
                        .build();
            }
        }

        // 3. Default: Device Status Query
        Map<String, Object> devStatus = toolsService.getDeviceStatus(classroomId, null);
        String statusText = String.format(
                "Classroom Device Summary:\n" +
                        "• Total Devices: %s\n" +
                        "• Online: %s\n" +
                        "• Offline: %s\n\n" +
                        "Visit the Devices tab to inspect individual device telemetry or toggle controls.",
                devStatus.get("totalDevices"),
                devStatus.get("onlineCount"),
                devStatus.get("offlineCount")
        );

        return AgentResponse.builder()
                .answer(statusText)
                .agentName(agentName())
                .data(devStatus)
                .build();
    }

    private boolean isControlIntent(String p) {
        return p.contains("turn on") || p.contains("turn off")
                || p.contains("switch on") || p.contains("switch off")
                || p.contains("power on") || p.contains("power off")
                || p.contains("shut down") || p.contains("start projector");
    }

    private boolean isSensorQuery(String p) {
        return p.contains("temperature") || p.contains("humidity")
                || p.contains("sensor") || p.contains("motion")
                || p.contains("gas") || p.contains("occupied")
                || p.contains("empty") || p.contains("environment");
    }

    private String extractTarget(String p) {
        if (p.contains("light")) return "LIGHT";
        if (p.contains("fan")) return "FAN";
        if (p.contains("projector")) return "PROJECTOR";
        return "ALL";
    }

    @Override
    protected String agentName() {
        return "DeviceAgent";
    }
}
