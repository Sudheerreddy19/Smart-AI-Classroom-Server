package com.finalYear.smartClassRoom.ai.tools;

import com.finalYear.smartClassRoom.entity.*;
import com.finalYear.smartClassRoom.repository.*;
import com.finalYear.smartClassRoom.service.AuditLogService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Server-side tools for Smart Classroom.
 * Each tool is a thin wrapper over existing repositories and services.
 * Strictly adheres to Free-Tier Privacy Rule: NEVER leaks individual student names,
 * marks, or personal attendance to external models.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ClassroomToolsService {

    private final EnvironmentDataRepository environmentDataRepository;
    private final DeviceRepository deviceRepository;
    private final ClassroomRepository classroomRepository;
    private final TimetableRepository timetableRepository;
    private final AttendanceSessionRepository attendanceSessionRepository;
    private final AuditLogService auditLogService;
    private final UserRepository userRepository;

    // ── 1. getCurrentSensorReadings ───────────────────────────────────────────
    @Transactional(readOnly = true)
    public Map<String, Object> getCurrentSensorReadings(Long classroomId) {
        Classroom classroom = resolveClassroom(classroomId);
        if (classroom == null) {
            return Map.of(
                    "status", "ERROR",
                    "message", "Classroom not found for ID: " + classroomId
            );
        }

        EnvironmentData latest = environmentDataRepository.findTopByClassroomOrderByRecordedAtDesc(classroom);
        if (latest == null) {
            return Map.of(
                    "status", "UNAVAILABLE",
                    "classroomId", classroom.getId(),
                    "roomNumber", classroom.getRoomNumber(),
                    "message", "No telemetry data recorded yet for " + classroom.getRoomNumber()
            );
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("status", "AVAILABLE");
        data.put("classroomId", classroom.getId());
        data.put("roomNumber", classroom.getRoomNumber());
        data.put("temperatureC", latest.getTemperature());
        data.put("humidityPercent", latest.getHumidity());
        data.put("lightLevel", latest.getLightLevel());
        data.put("gasLeakDetected", Boolean.TRUE.equals(latest.getGasLeak()));
        data.put("motionDetected", Boolean.TRUE.equals(latest.getMotionDetected()));
        data.put("roomOccupancyState", latest.getRoomState() != null ? latest.getRoomState() : "UNKNOWN");
        data.put("fanStatus", latest.getFanStatus() != null ? latest.getFanStatus() : "OFF");
        data.put("lightStatus", latest.getLightStatus() != null ? latest.getLightStatus() : "OFF");
        data.put("recordedAt", latest.getRecordedAt() != null ? latest.getRecordedAt().toString() : "N/A");

        return data;
    }

    // ── 2. getDeviceStatus ────────────────────────────────────────────────────
    @Transactional(readOnly = true)
    public Map<String, Object> getDeviceStatus(Long classroomId, String deviceId) {
        if (deviceId != null && !deviceId.isBlank()) {
            Optional<Device> deviceOpt = deviceRepository.findByDeviceId(deviceId);
            if (deviceOpt.isPresent()) {
                Device d = deviceOpt.get();
                return Map.of(
                        "status", "SUCCESS",
                        "deviceId", d.getDeviceId(),
                        "deviceName", d.getDeviceName(),
                        "deviceType", d.getDeviceType().name(),
                        "onlineStatus", d.getStatus().name(),
                        "roomNumber", d.getClassroom() != null ? d.getClassroom().getRoomNumber() : "Unassigned"
                );
            }
        }

        Classroom classroom = resolveClassroom(classroomId);
        List<Device> devices = (classroom != null)
                ? deviceRepository.findByClassroom(classroom)
                : deviceRepository.findAll();

        long onlineCount = devices.stream().filter(d -> d.getStatus() == Device.DeviceStatus.ONLINE).count();

        List<Map<String, Object>> deviceList = devices.stream().map(d -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("deviceId", d.getDeviceId());
            m.put("deviceName", d.getDeviceName());
            m.put("deviceType", d.getDeviceType().name());
            m.put("status", d.getStatus().name());
            m.put("lastSeen", d.getLastSeen() != null ? d.getLastSeen().toString() : "Never");
            return m;
        }).collect(Collectors.toList());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "SUCCESS");
        result.put("classroomId", classroom != null ? classroom.getId() : null);
        result.put("totalDevices", devices.size());
        result.put("onlineCount", onlineCount);
        result.put("offlineCount", devices.size() - onlineCount);
        result.put("devices", deviceList);

        return result;
    }

    // ── 3. getCurrentTimetable ────────────────────────────────────────────────
    @Transactional(readOnly = true)
    public Map<String, Object> getCurrentTimetable(Long classroomId) {
        return getCurrentTimetable(classroomId, LocalDate.now(), LocalTime.now());
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getCurrentTimetable(Long classroomId, LocalDate today, LocalTime now) {
        Classroom classroom = resolveClassroom(classroomId);
        if (classroom == null) {
            return Map.of("status", "ERROR", "message", "Classroom not found");
        }

        Timetable.DayOfWeek dayOfWeek = mapDayOfWeek(today.getDayOfWeek());

        List<Timetable> slots = (dayOfWeek != null)
                ? timetableRepository.findByClassroomAndDayOfWeek(classroom, dayOfWeek)
                : Collections.emptyList();

        Optional<Timetable> currentSlot = slots.stream()
                .filter(Timetable::isActive)
                .filter(t -> !now.isBefore(t.getStartTime()) && !now.isAfter(t.getEndTime()))
                .findFirst();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("roomNumber", classroom.getRoomNumber());
        result.put("dayOfWeek", dayOfWeek != null ? dayOfWeek.name() : "SUNDAY");
        result.put("currentTime", now.format(DateTimeFormatter.ofPattern("HH:mm")));

        if (currentSlot.isPresent()) {
            Timetable slot = currentSlot.get();
            result.put("status", "IN_SESSION");
            result.put("subject", slot.getSubject() != null ? slot.getSubject().getName() : "General Class");
            result.put("teacher", slot.getTeacher() != null ? slot.getTeacher().getUser().getFirstName() + " " + slot.getTeacher().getUser().getLastName() : "Faculty");
            result.put("startTime", slot.getStartTime().toString());
            result.put("endTime", slot.getEndTime().toString());
        } else {
            result.put("status", "NO_ACTIVE_CLASS");
            result.put("message", "No class is currently in session for " + classroom.getRoomNumber());

            // Check if there is a next class later today
            Optional<Timetable> nextSlot = slots.stream()
                    .filter(Timetable::isActive)
                    .filter(t -> t.getStartTime().isAfter(now))
                    .min(Comparator.comparing(Timetable::getStartTime));

            nextSlot.ifPresent(next -> {
                result.put("nextClassSubject", next.getSubject() != null ? next.getSubject().getName() : "Upcoming Class");
                result.put("nextClassStartsAt", next.getStartTime().toString());
            });
        }

        return result;
    }

    // ── 4. getUpcomingClasses ─────────────────────────────────────────────────
    @Transactional(readOnly = true)
    public Map<String, Object> getUpcomingClasses(Long classroomId, Integer limit) {
        return getUpcomingClasses(classroomId, limit, LocalDate.now(), LocalTime.now());
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getUpcomingClasses(Long classroomId, Integer limit, LocalDate today, LocalTime now) {
        Classroom classroom = resolveClassroom(classroomId);
        if (classroom == null) {
            return Map.of("status", "ERROR", "message", "Classroom not found");
        }

        int max = (limit != null && limit > 0) ? limit : 5;
        Timetable.DayOfWeek dayOfWeek = mapDayOfWeek(today.getDayOfWeek());

        List<Timetable> slots = (dayOfWeek != null)
                ? timetableRepository.findByClassroomAndDayOfWeek(classroom, dayOfWeek)
                : Collections.emptyList();

        List<Map<String, Object>> upcoming = slots.stream()
                .filter(Timetable::isActive)
                .filter(t -> t.getStartTime().isAfter(now))
                .sorted(Comparator.comparing(Timetable::getStartTime))
                .limit(max)
                .map(t -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("subject", t.getSubject() != null ? t.getSubject().getName() : "Subject");
                    m.put("startTime", t.getStartTime().toString());
                    m.put("endTime", t.getEndTime().toString());
                    m.put("teacher", t.getTeacher() != null ? t.getTeacher().getUser().getFirstName() + " " + t.getTeacher().getUser().getLastName() : "Assigned Faculty");
                    return m;
                })
                .collect(Collectors.toList());

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", "SUCCESS");
        response.put("roomNumber", classroom.getRoomNumber());
        response.put("dayOfWeek", dayOfWeek != null ? dayOfWeek.name() : "SUNDAY");
        response.put("upcomingCount", upcoming.size());
        response.put("classes", upcoming);

        return response;
    }

    // ── 5. getAttendanceSummary (Aggregate Only - Privacy Compliant) ──────────
    @Transactional(readOnly = true)
    public Map<String, Object> getAttendanceSummary(Long classroomId, String dateStr) {
        LocalDate date = (dateStr != null && !dateStr.isBlank())
                ? LocalDate.parse(dateStr)
                : LocalDate.now();

        Classroom classroom = resolveClassroom(classroomId);

        List<AttendanceSession> sessions = (classroom != null)
                ? attendanceSessionRepository.findByClassroom(classroom)
                : attendanceSessionRepository.findBySessionDate(date);

        if (sessions.isEmpty()) {
            return Map.of(
                    "status", "NO_DATA",
                    "classroomId", classroom != null ? classroom.getId() : null,
                    "date", date.toString(),
                    "message", "No attendance sessions recorded for this date/classroom."
            );
        }

        int totalStudentsScheduled = 0;
        int totalPresent = 0;
        int totalSessions = sessions.size();

        for (AttendanceSession s : sessions) {
            if (s.getTotalStudents() != null) totalStudentsScheduled += s.getTotalStudents();
            if (s.getPresentCount() != null) totalPresent += s.getPresentCount();
        }

        int totalAbsent = Math.max(0, totalStudentsScheduled - totalPresent);
        double percentage = (totalStudentsScheduled > 0)
                ? (totalPresent * 100.0 / totalStudentsScheduled)
                : 0.0;

        String healthTone = (percentage >= 85.0) ? "OPTIMAL" : (percentage >= 70.0) ? "NORMAL" : "ATTENTION_NEEDED";

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("status", "SUCCESS");
        summary.put("date", date.toString());
        summary.put("classroom", classroom != null ? classroom.getRoomNumber() : "All Classrooms");
        summary.put("totalSessions", totalSessions);
        summary.put("totalStudentSlots", totalStudentsScheduled);
        summary.put("totalPresent", totalPresent);
        summary.put("totalAbsent", totalAbsent);
        summary.put("attendancePercentage", String.format(Locale.ROOT, "%.1f%%", percentage));
        summary.put("attendanceHealth", healthTone);
        summary.put("privacyNotice", "Aggregated only. No individual student identities are exposed.");

        return summary;
    }

    // ── 6. executeDeviceControl (RBAC, Safety Checks, Confirmation & Audit Log) ──
    @Transactional
    public Map<String, Object> executeDeviceControl(Long classroomId, String target, String action, boolean confirmed, User user) {
        // A. RBAC Check: Only TEACHER, ADMIN, HOD, SUPER_ADMIN permitted
        if (user == null || user.getRole() == User.Role.STUDENT) {
            return Map.of(
                    "status", "FORBIDDEN",
                    "executed", false,
                    "message", "You don't have permission to control classroom devices. Only teachers and administrators can perform this action."
            );
        }

        Classroom classroom = resolveClassroom(classroomId);
        if (classroom == null) {
            return Map.of(
                    "status", "ERROR",
                    "executed", false,
                    "message", "Classroom not found for ID: " + classroomId
            );
        }

        String targetNorm = (target != null) ? target.trim().toUpperCase(Locale.ROOT) : "ALL";
        String actionNorm = (action != null) ? action.trim().toUpperCase(Locale.ROOT) : "OFF";

        // B. Safety Checks
        List<String> safetyWarnings = new ArrayList<>();

        if ("OFF".equals(actionNorm)) {
            // Safety Check 1: Motion detected in last 5 minutes when turning off lights or all power
            if ("LIGHT".equals(targetNorm) || "LIGHTS".equals(targetNorm) || "ALL".equals(targetNorm) || "POWER".equals(targetNorm)) {
                EnvironmentData latest = environmentDataRepository.findTopByClassroomOrderByRecordedAtDesc(classroom);
                LocalDateTime fiveMinutesAgo = LocalDateTime.now().minusMinutes(5);
                boolean recentMotion = latest != null && (
                        (latest.getRecordedAt() != null && latest.getRecordedAt().isAfter(fiveMinutesAgo) && Boolean.TRUE.equals(latest.getMotionDetected()))
                        || "ACTIVE".equalsIgnoreCase(latest.getRoomState())
                        || "OCCUPIED".equalsIgnoreCase(latest.getRoomState())
                );
                if (recentMotion) {
                    safetyWarnings.add("Safety warning: Motion was detected in the classroom within the last 5 minutes. Students or staff may still be present inside.");
                }
            }

            // Safety Check 2: Projector turned off during an active timetable class
            if ("PROJECTOR".equals(targetNorm) || "ALL".equals(targetNorm) || "POWER".equals(targetNorm)) {
                Map<String, Object> tt = getCurrentTimetable(classroom.getId());
                if ("IN_SESSION".equals(tt.get("status"))) {
                    safetyWarnings.add(String.format(
                            "Safety warning: Class '%s' taught by %s is currently in session until %s. Turning off the %s may disrupt instruction.",
                            tt.get("subject"), tt.get("teacher"), tt.get("endTime"), targetNorm.toLowerCase()
                    ));
                }
            }
        }

        // C. Confirmation Step: If not explicitly confirmed, return confirmation preview
        if (!confirmed) {
            String previewMsg = String.format("Are you sure you want to turn %s %s in %s?",
                    actionNorm, targetNorm, classroom.getRoomNumber());

            Map<String, Object> preview = new LinkedHashMap<>();
            preview.put("status", "CONFIRMATION_REQUIRED");
            preview.put("requiresConfirmation", true);
            preview.put("previewMessage", previewMsg);
            preview.put("safetyWarnings", safetyWarnings);
            preview.put("target", targetNorm);
            preview.put("action", actionNorm);
            preview.put("classroomId", classroom.getId());
            preview.put("roomNumber", classroom.getRoomNumber());
            return preview;
        }

        // D. Execution Step: Perform state changes
        List<Device> classroomDevices = deviceRepository.findByClassroom(classroom);
        Device.DeviceStatus targetStatus = "ON".equals(actionNorm) ? Device.DeviceStatus.ONLINE : Device.DeviceStatus.OFFLINE;

        String previousState = "OFF";
        int affectedCount = 0;

        if ("ALL".equals(targetNorm) || "POWER".equals(targetNorm)) {
            for (Device d : classroomDevices) {
                previousState = d.getStatus().name();
                d.setStatus(targetStatus);
                deviceRepository.save(d);
                affectedCount++;
            }
        } else if ("PROJECTOR".equals(targetNorm)) {
            for (Device d : classroomDevices) {
                if (d.getDeviceType() == Device.DeviceType.PROJECTOR || d.getDeviceName().toLowerCase().contains("projector")) {
                    previousState = d.getStatus().name();
                    d.setStatus(targetStatus);
                    deviceRepository.save(d);
                    affectedCount++;
                }
            }
        } else {
            // Specific device ID or generic Light/Fan relay
            Optional<Device> specific = deviceRepository.findByDeviceId(target);
            if (specific.isPresent()) {
                Device d = specific.get();
                previousState = d.getStatus().name();
                d.setStatus(targetStatus);
                deviceRepository.save(d);
                affectedCount++;
            } else {
                // Look for matching relay/type in classroom
                for (Device d : classroomDevices) {
                    if (d.getDeviceName().toUpperCase().contains(targetNorm) || d.getDeviceType().name().contains(targetNorm)) {
                        previousState = d.getStatus().name();
                        d.setStatus(targetStatus);
                        deviceRepository.save(d);
                        affectedCount++;
                    }
                }
            }
        }

        // Also reflect in latest EnvironmentData for immediate sensor telemetry query consistency
        EnvironmentData latest = environmentDataRepository.findTopByClassroomOrderByRecordedAtDesc(classroom);
        if (latest != null) {
            if ("LIGHT".equals(targetNorm) || "LIGHTS".equals(targetNorm) || "ALL".equals(targetNorm)) {
                latest.setLightStatus(actionNorm);
            }
            if ("FAN".equals(targetNorm) || "FANS".equals(targetNorm) || "ALL".equals(targetNorm)) {
                latest.setFanStatus(actionNorm);
            }
            environmentDataRepository.save(latest);
        }

        // E. Audit Log Action
        String auditDetails = String.format("User %s (%s) set %s to %s in %s. Devices affected: %d. Previous state: %s, New state: %s.",
                user.getEmail(), user.getRole(), targetNorm, actionNorm, classroom.getRoomNumber(), affectedCount, previousState, actionNorm);

        auditLogService.log("AI_DEVICE_CONTROL", "Classroom", classroom.getId(), auditDetails);

        Map<String, Object> success = new LinkedHashMap<>();
        success.put("status", "SUCCESS");
        success.put("executed", true);
        success.put("message", String.format("Successfully turned %s %s in %s.", actionNorm, targetNorm, classroom.getRoomNumber()));
        success.put("target", targetNorm);
        success.put("action", actionNorm);
        success.put("classroomId", classroom.getId());
        success.put("roomNumber", classroom.getRoomNumber());
        success.put("affectedDevices", affectedCount);
        success.put("previousState", previousState);
        success.put("newState", actionNorm);
        success.put("safetyWarnings", safetyWarnings);
        success.put("timestamp", LocalDateTime.now().toString());

        return success;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────
    public Classroom resolveClassroom(Long classroomId) {
        if (classroomId != null) {
            return classroomRepository.findById(classroomId).orElse(null);
        }
        List<Classroom> active = classroomRepository.findByActiveTrue();
        if (!active.isEmpty()) return active.get(0);
        List<Classroom> all = classroomRepository.findAll();
        return all.isEmpty() ? null : all.get(0);
    }

    private Timetable.DayOfWeek mapDayOfWeek(java.time.DayOfWeek dow) {
        return switch (dow) {
            case MONDAY -> Timetable.DayOfWeek.MONDAY;
            case TUESDAY -> Timetable.DayOfWeek.TUESDAY;
            case WEDNESDAY -> Timetable.DayOfWeek.WEDNESDAY;
            case THURSDAY -> Timetable.DayOfWeek.THURSDAY;
            case FRIDAY -> Timetable.DayOfWeek.FRIDAY;
            case SATURDAY -> Timetable.DayOfWeek.SATURDAY;
            case SUNDAY -> null;
        };
    }
}
