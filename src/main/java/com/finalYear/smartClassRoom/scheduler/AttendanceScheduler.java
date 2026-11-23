package com.finalYear.smartClassRoom.scheduler;

import com.finalYear.smartClassRoom.dto.request.AttendanceSessionRequest;
import com.finalYear.smartClassRoom.entity.AttendanceSession;
import com.finalYear.smartClassRoom.entity.Classroom;
import com.finalYear.smartClassRoom.entity.Timetable;
import com.finalYear.smartClassRoom.repository.AttendanceSessionRepository;
import com.finalYear.smartClassRoom.repository.ClassroomRepository;
import com.finalYear.smartClassRoom.repository.TimetableRepository;
import com.finalYear.smartClassRoom.service.AttendanceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

@Component
@RequiredArgsConstructor
@Slf4j
public class AttendanceScheduler {

    private final AttendanceSessionRepository attendanceSessionRepository;
    private final AttendanceService           attendanceService;
    private final TimetableRepository         timetableRepository;
    private final ClassroomRepository         classroomRepository;

    /**
     * Runs every minute:
     * 1. Checks if a class period has reached its halfway mark without attendance being taken.
     *    If so, automatically initiates attendance recognition!
     * 2. Automatically closes expired sessions, marks all non-recognized enrolled students
     *    as ABSENT, and dispatches the attendance report to Admin.
     */
    @Scheduled(fixedRate = 60000) // Every minute
    public void monitorClassroomAttendance() {
        LocalDate today = LocalDate.now();
        LocalTime now = LocalTime.now();

        // ── 1. Automatic Attendance Trigger After Half-Period ─────────────────
        Timetable.DayOfWeek dow = switch (today.getDayOfWeek()) {
            case MONDAY -> Timetable.DayOfWeek.MONDAY;
            case TUESDAY -> Timetable.DayOfWeek.TUESDAY;
            case WEDNESDAY -> Timetable.DayOfWeek.WEDNESDAY;
            case THURSDAY -> Timetable.DayOfWeek.THURSDAY;
            case FRIDAY -> Timetable.DayOfWeek.FRIDAY;
            case SATURDAY -> Timetable.DayOfWeek.SATURDAY;
            default -> null;
        };

        if (dow != null) {
            List<Timetable> activeSlots = timetableRepository.findByActiveTrue()
                    .stream()
                    .filter(t -> t.getDayOfWeek() == dow)
                    .filter(t -> t.getStartTime() != null && t.getEndTime() != null)
                    .filter(t -> !now.isBefore(t.getStartTime()) && !now.isAfter(t.getEndTime()))
                    .toList();

            for (Timetable slot : activeSlots) {
                if (slot.getClassroom() == null) continue;

                // Calculate duration and halfway mark
                Duration totalDuration = Duration.between(slot.getStartTime(), slot.getEndTime());
                LocalTime halfwayTime = slot.getStartTime().plusMinutes(totalDuration.toMinutes() / 2);

                // If current time has passed the halfway mark of the lecture period
                if (now.isAfter(halfwayTime) || now.equals(halfwayTime)) {
                    // Check if an attendance session was already started for this slot today
                    boolean sessionExists = attendanceSessionRepository.findBySessionDate(today)
                            .stream()
                            .anyMatch(s -> s.getClassroom() != null
                                    && s.getClassroom().getId().equals(slot.getClassroom().getId())
                                    && s.getTimetable() != null
                                    && s.getTimetable().getId().equals(slot.getId()));

                    if (!sessionExists) {
                        log.info("[AUTO-ATTENDANCE] Half-period passed for {} in {}. Auto-starting attendance session!",
                                slot.getSubject() != null ? slot.getSubject().getName() : "Subject",
                                slot.getClassroom().getRoomNumber());

                        try {
                            AttendanceSessionRequest req = new AttendanceSessionRequest();
                            req.setClassroomId(slot.getClassroom().getId());
                            if (slot.getSubject() != null) req.setSubjectId(slot.getSubject().getId());
                            if (slot.getTeacher() != null) req.setTeacherId(slot.getTeacher().getId());
                            req.setSessionDate(today);
                            req.setStartTime(now);

                            attendanceService.startFaceAttendanceSession(req);
                        } catch (Exception e) {
                            log.warn("[AUTO-ATTENDANCE] Could not auto-trigger attendance: {}", e.getMessage());
                        }
                    }
                }
            }
        }

        // ── 2. Auto-close Expired Sessions & Mark Absentees ───────────────────
        attendanceSessionRepository.findAll()
                .stream()
                .filter(session ->
                        session.getStatus() == AttendanceSession.SessionStatus.ACTIVE
                                && session.getSessionDate().equals(today)
                                && session.getEndTime() != null
                                && session.getEndTime().isBefore(now))
                .forEach(session -> {
                    try {
                        log.info("[AUTO-ATTENDANCE] Period ended for Session #{}. Auto-completing session and marking absentees.",
                                session.getId());
                        attendanceService.closeSession(session.getId());
                    } catch (Exception e) {
                        log.error("[AUTO-ATTENDANCE] Error auto-closing session #{}: {}", session.getId(), e.getMessage());
                    }
                });
    }
}