package com.finalYear.smartClassRoom.ai.orchestrator;

import com.finalYear.smartClassRoom.ai.agent.*;
import com.finalYear.smartClassRoom.ai.model.AgentRequest;
import com.finalYear.smartClassRoom.ai.model.AgentResponse;
import com.finalYear.smartClassRoom.entity.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class AIOrchestrator {

    private final ChatAgent          chatAgent;
    private final AttendanceAgent    attendanceAgent;
    private final MarksAgent         marksAgent;
    private final TimetableAgent     timetableAgent;
    private final StudentAgent       studentAgent;
    private final TeacherAgent       teacherAgent;
    private final DeviceAgent        deviceAgent;
    private final ReportAgent        reportAgent;
    private final VideoAgent         videoAgent;
    private final QuizAgent          quizAgent;

    public AgentResponse route(AgentRequest request) {
        String prompt    = request.getPrompt().toLowerCase();
        User.Role role   = request.getRole();

        log.info("Orchestrator routing: userId={} role={} prompt={}...",
            request.getUserId(), role, prompt.substring(0, Math.min(50, prompt.length())));

        // ── Role-specific routing ──────────────────────────────────────────────
        if (role == User.Role.STUDENT) {
            // Students only get subject explanations, personal data, timetable, and sensor telemetry; control requests are RBAC-rejected
            if (isAttendanceQuery(prompt))  return attendanceAgent.handle(request);
            if (isMarksQuery(prompt))       return marksAgent.handle(request);
            if (isTimetableQuery(prompt))   return timetableAgent.handle(request);
            if (isDeviceQuery(prompt))      return deviceAgent.handle(request);
            if (isQuizRequest(prompt))      return quizAgent.handle(request);
            if (isVideoRequest(prompt))     return videoAgent.handle(request);
            return chatAgent.handle(request);   // subject explanations via provider
        }

        // ── Staff routing (ADMIN, HOD, TEACHER, SUPER_ADMIN) ──────────────────
        if (isAttendanceQuery(prompt))      return attendanceAgent.handle(request);
        if (isMarksQuery(prompt))           return marksAgent.handle(request);
        if (isTimetableQuery(prompt))       return timetableAgent.handle(request);
        if (isStudentQuery(prompt))         return studentAgent.handle(request);
        if (isTeacherQuery(prompt))         return teacherAgent.handle(request);
        if (isDeviceQuery(prompt))          return deviceAgent.handle(request);
        if (isReportRequest(prompt))        return reportAgent.handle(request);
        if (isQuizRequest(prompt))          return quizAgent.handle(request);
        if (isVideoRequest(prompt))         return videoAgent.handle(request);

        // Default: general AI chat via provider
        return chatAgent.handle(request);
    }

    // ── Intent detection helpers ───────────────────────────────────────────────
    private boolean isAttendanceQuery(String p)  { return p.contains("attendance") || p.contains("present") || p.contains("absent") || p.contains("late"); }
    private boolean isMarksQuery(String p)        { return p.contains("mark") || p.contains("grade") || p.contains("score") || p.contains("result") || p.contains("exam"); }
    private boolean isTimetableQuery(String p)    { return p.contains("timetable") || p.contains("schedule") || p.contains("class today") || p.contains("when is"); }
    private boolean isStudentQuery(String p)      { return p.contains("student") || p.contains("enroll") || p.contains("at risk") || p.contains("weak student"); }
    private boolean isTeacherQuery(String p)      { return p.contains("teacher") || p.contains("faculty") || p.contains("who teaches"); }
    private boolean isDeviceQuery(String p)       {
        return p.contains("device") || p.contains("sensor") || p.contains("temperature")
            || p.contains("humidity") || p.contains("iot") || p.contains("camera")
            || p.contains("light") || p.contains("fan") || p.contains("projector")
            || p.contains("turn on") || p.contains("turn off") || p.contains("switch on")
            || p.contains("switch off") || p.contains("power");
    }
    private boolean isReportRequest(String p)     { return p.contains("report") || p.contains("summary") || p.contains("generate") || p.contains("analytics"); }
    private boolean isQuizRequest(String p)       { return p.contains("quiz") || p.contains("question") || p.contains("mcq") || p.contains("test me"); }
    private boolean isVideoRequest(String p)      { return p.contains("video") || p.contains("tutorial") || p.contains("youtube") || p.contains("recommend"); }
}
