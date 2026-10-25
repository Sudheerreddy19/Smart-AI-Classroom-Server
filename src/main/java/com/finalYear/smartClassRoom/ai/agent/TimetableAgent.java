package com.finalYear.smartClassRoom.ai.agent;

import com.finalYear.smartClassRoom.ai.model.AgentRequest;
import com.finalYear.smartClassRoom.ai.model.AgentResponse;
import com.finalYear.smartClassRoom.ai.tools.ClassroomToolsService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class TimetableAgent extends BaseAgent {

    private final ClassroomToolsService toolsService;

    @Override
    public AgentResponse handle(AgentRequest request) {
        Long classroomId = request.getClassroomId();
        Map<String, Object> current = toolsService.getCurrentTimetable(classroomId);
        Map<String, Object> upcoming = toolsService.getUpcomingClasses(classroomId, 3);

        StringBuilder sb = new StringBuilder();

        if ("IN_SESSION".equals(current.get("status"))) {
            sb.append("Current Class in Session (").append(current.get("roomNumber")).append("):\n")
                    .append("• Subject: ").append(current.get("subject")).append("\n")
                    .append("• Teacher: ").append(current.get("teacher")).append("\n")
                    .append("• Time: ").append(current.get("startTime")).append(" – ").append(current.get("endTime")).append("\n\n");
        } else {
            sb.append("No class is currently in session for ").append(current.get("roomNumber") != null ? current.get("roomNumber") : "the classroom").append(".\n");
            if (current.get("nextClassSubject") != null) {
                sb.append("• Next Class: ").append(current.get("nextClassSubject"))
                        .append(" at ").append(current.get("nextClassStartsAt")).append("\n\n");
            } else {
                sb.append("\n");
            }
        }

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> classes = (List<Map<String, Object>>) upcoming.get("classes");
        if (classes != null && !classes.isEmpty()) {
            sb.append("Upcoming Classes Today:\n");
            for (Map<String, Object> c : classes) {
                sb.append("• ").append(c.get("startTime")).append(" – ").append(c.get("subject"))
                        .append(" (").append(c.get("teacher")).append(")\n");
            }
        }

        return AgentResponse.builder()
                .answer(sb.toString().trim())
                .agentName(agentName())
                .data(Map.of("current", current, "upcoming", upcoming))
                .build();
    }

    @Override
    protected String agentName() {
        return "TimetableAgent";
    }
}
