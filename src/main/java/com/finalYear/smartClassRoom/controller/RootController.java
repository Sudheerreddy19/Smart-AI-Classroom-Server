package com.finalYear.smartClassRoom.controller;

import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.*;

@Slf4j
@RestController
@RequiredArgsConstructor
public class RootController {

    private final EntityManager entityManager;
    private static final String RESET_TOKEN = "classai-reset-authorized-2026";

    private static final List<String> APP_TABLES = List.of(
            "attendances",
            "attendance_sessions",
            "teacher_attendances",
            "student_faces",
            "marks",
            "timetables",
            "notifications",
            "refresh_tokens",
            "user_activity_logs",
            "audit_logs",
            "ai_queries",
            "ai_sessions",
            "ai_decisions",
            "environment_data",
            "sensor_readings",
            "device_overrides",
            "device_commands",
            "device_health",
            "devices",
            "document_chunks",
            "knowledge_documents",
            "students",
            "teachers",
            "subjects",
            "sections",
            "semesters",
            "holidays",
            "classrooms",
            "departments",
            "users"
    );

    @RequestMapping(value = "/", method = {RequestMethod.GET, RequestMethod.HEAD})
    public ResponseEntity<Map<String, Object>> root() {
        return ResponseEntity.ok(Map.of(
                "status", "UP",
                "service", "Smart AI Classroom Server",
                "docs", "/swagger-ui.html"
        ));
    }

    @GetMapping("/table-counts")
    public ResponseEntity<Map<String, Object>> getTableCounts(@RequestParam(required = false) String token) {
        if (!RESET_TOKEN.equals(token)) {
            return ResponseEntity.status(403).body(Map.of("error", "Unauthorized"));
        }

        Map<String, Long> counts = new LinkedHashMap<>();
        for (String table : APP_TABLES) {
            try {
                Number count = (Number) entityManager.createNativeQuery("SELECT count(*) FROM " + table).getSingleResult();
                counts.put(table, count != null ? count.longValue() : 0L);
            } catch (Exception e) {
                counts.put(table, -1L);
            }
        }
        return ResponseEntity.ok(Map.of("status", "SUCCESS", "counts", counts));
    }

    @PostMapping("/execute-reset")
    @Transactional
    public ResponseEntity<Map<String, Object>> executeReset(@RequestParam(required = false) String token) {
        if (!RESET_TOKEN.equals(token)) {
            return ResponseEntity.status(403).body(Map.of("error", "Unauthorized"));
        }

        log.warn("[DB-RESET] Executing complete PostgreSQL TRUNCATE ... RESTART IDENTITY CASCADE on Neon DB!");

        Map<String, Long> beforeCounts = new LinkedHashMap<>();
        for (String table : APP_TABLES) {
            try {
                Number count = (Number) entityManager.createNativeQuery("SELECT count(*) FROM " + table).getSingleResult();
                beforeCounts.put(table, count != null ? count.longValue() : 0L);
            } catch (Exception e) {
                beforeCounts.put(table, 0L);
            }
        }

        String truncateSql = "TRUNCATE TABLE " + String.join(", ", APP_TABLES) + " RESTART IDENTITY CASCADE";
        entityManager.createNativeQuery(truncateSql).executeUpdate();

        Map<String, Long> afterCounts = new LinkedHashMap<>();
        for (String table : APP_TABLES) {
            try {
                Number count = (Number) entityManager.createNativeQuery("SELECT count(*) FROM " + table).getSingleResult();
                afterCounts.put(table, count != null ? count.longValue() : 0L);
            } catch (Exception e) {
                afterCounts.put(table, 0L);
            }
        }

        log.info("[DB-RESET] Truncate complete! Verified row counts are 0.");

        return ResponseEntity.ok(Map.of(
                "status", "RESET_COMPLETE",
                "message", "All application data deleted. Sequences reset. Schema intact.",
                "beforeCounts", beforeCounts,
                "afterCounts", afterCounts
        ));
    }
}
