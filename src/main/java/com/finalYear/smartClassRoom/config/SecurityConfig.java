package com.finalYear.smartClassRoom.config;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableMethodSecurity          // enables @PreAuthorize on controllers/services
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthenticationFilter     jwtAuthenticationFilter;
    private final JwtAuthenticationEntryPoint jwtAuthenticationEntryPoint;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {

        http
            .csrf(csrf -> csrf.disable())
            .cors(Customizer.withDefaults())
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .exceptionHandling(e -> e.authenticationEntryPoint(jwtAuthenticationEntryPoint))

            .authorizeHttpRequests(auth -> auth

                // ── Public: Auth endpoints ────────────────────────────────────
                .requestMatchers("/api/auth/**").permitAll()

                // ── Public: Swagger / OpenAPI ─────────────────────────────────
                .requestMatchers(
                    "/swagger-ui/**", "/swagger-ui.html",
                    "/v3/api-docs/**", "/api/swagger-ui/**",
                    "/api/v3/api-docs/**"
                ).permitAll()

                // ── Public: WebSocket ─────────────────────────────────────────
                .requestMatchers("/ws/**").permitAll()

                // ── Public: Actuator health ───────────────────────────────────
                .requestMatchers("/actuator/**").permitAll()

                // ── Public: Frontend SPA, static assets & icons ─────────────────
                .requestMatchers(
                    "/", "/index.html", "/favicon.ico", "/favicon.svg", "/icons.svg", "/vite.svg",
                    "/assets/**", "/data/**", "/static/**", "/error",
                    "/*.js", "/*.css", "/*.png", "/*.jpg", "/*.svg", "/*.ico", "/*.json"
                ).permitAll()
                .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()

                // ── Public: Department + Semester list for registration form ──
                .requestMatchers(HttpMethod.GET, "/api/departments").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/semesters").permitAll()

                // ── IoT: Hardware telemetry & heartbeat (no auth needed from hardware) ──
                .requestMatchers("/api/devices/*/heartbeat").permitAll()
                .requestMatchers("/api/environment/ingest", "/api/iot/telemetry").permitAll()

                // ── SUPER_ADMIN only ──────────────────────────────────────────
                .requestMatchers("/api/super-admin/**")
                    .hasRole("SUPER_ADMIN")

                // ── User CRUD (role-hierarchy enforced in controller) ───────────
                // SUPER_ADMIN → ADMIN, HOD, TEACHER, STUDENT
                // ADMIN       → HOD, TEACHER, STUDENT
                // HOD         → TEACHER, STUDENT
                // TEACHER     → STUDENT
                // STUDENT     → none
                .requestMatchers("/api/users/**")
                    .hasAnyRole("SUPER_ADMIN", "ADMIN", "HOD", "TEACHER")

                // ── User creation: HOD + TEACHER can also create users ────────
                // Must be listed BEFORE the /api/admin/** catch-all below
                .requestMatchers("/api/admin/users", "/api/admin/users/**")
                    .hasAnyRole("SUPER_ADMIN", "ADMIN", "HOD", "TEACHER")

                // ── Admin: full management (SUPER_ADMIN + ADMIN) ──────────────
                .requestMatchers("/api/admin/**")
                    .hasAnyRole("SUPER_ADMIN", "ADMIN")

                // ── HOD: department-scoped management ─────────────────────────
                .requestMatchers("/api/hod/**")
                    .hasAnyRole("SUPER_ADMIN", "ADMIN", "HOD")

                // ── Teacher: assigned-class management ────────────────────────
                .requestMatchers("/api/teacher/**")
                    .hasAnyRole("SUPER_ADMIN", "ADMIN", "HOD", "TEACHER")

                // ── Student: own data only ────────────────────────────────────
                .requestMatchers("/api/student/**")
                    .hasAnyRole("SUPER_ADMIN", "ADMIN", "HOD", "TEACHER", "STUDENT")

                // ── Departments, Semesters (ADMIN+) ───────────────────────────
                .requestMatchers(HttpMethod.GET, "/api/departments/**")
                    .hasAnyRole("SUPER_ADMIN", "ADMIN", "HOD", "TEACHER", "STUDENT")
                .requestMatchers("/api/departments/**")
                    .hasAnyRole("SUPER_ADMIN", "ADMIN", "HOD")

                .requestMatchers(HttpMethod.GET, "/api/semesters/**")
                    .hasAnyRole("SUPER_ADMIN", "ADMIN", "HOD", "TEACHER", "STUDENT")
                .requestMatchers("/api/semesters/**")
                    .hasAnyRole("SUPER_ADMIN", "ADMIN", "HOD")

                // ── Students (read: TEACHER+; write: validated by RoleValidator in controller) ──
                .requestMatchers(HttpMethod.GET, "/api/students/**")
                    .hasAnyRole("SUPER_ADMIN", "ADMIN", "HOD", "TEACHER", "STUDENT")
                .requestMatchers(HttpMethod.POST, "/api/students/**")
                    .hasAnyRole("SUPER_ADMIN", "ADMIN", "HOD", "TEACHER")
                .requestMatchers(HttpMethod.PUT, "/api/students/**")
                    .hasAnyRole("SUPER_ADMIN", "ADMIN", "HOD", "TEACHER")
                .requestMatchers(HttpMethod.DELETE, "/api/students/**")
                    .hasAnyRole("SUPER_ADMIN", "ADMIN", "HOD", "TEACHER")

                // ── Teachers ──────────────────────────────────────────────────
                .requestMatchers(HttpMethod.GET, "/api/teachers/**")
                    .hasAnyRole("SUPER_ADMIN", "ADMIN", "HOD", "TEACHER")
                .requestMatchers("/api/teachers/**")
                    .hasAnyRole("SUPER_ADMIN", "ADMIN", "HOD")

                // ── Subjects ──────────────────────────────────────────────────
                .requestMatchers(HttpMethod.GET, "/api/subjects/**")
                    .hasAnyRole("SUPER_ADMIN", "ADMIN", "HOD", "TEACHER", "STUDENT")
                .requestMatchers("/api/subjects/**")
                    .hasAnyRole("SUPER_ADMIN", "ADMIN", "HOD")

                // ── Classrooms ────────────────────────────────────────────────
                .requestMatchers(HttpMethod.GET, "/api/classrooms/**")
                    .hasAnyRole("SUPER_ADMIN", "ADMIN", "HOD", "TEACHER", "STUDENT")
                .requestMatchers("/api/classrooms/**")
                    .hasAnyRole("SUPER_ADMIN", "ADMIN")

                // ── Timetable ─────────────────────────────────────────────────
                .requestMatchers(HttpMethod.GET, "/api/timetables/**")
                    .hasAnyRole("SUPER_ADMIN", "ADMIN", "HOD", "TEACHER", "STUDENT")
                .requestMatchers("/api/timetables/**")
                    .hasAnyRole("SUPER_ADMIN", "ADMIN", "HOD")

                // ── Attendance ────────────────────────────────────────────────
                .requestMatchers(HttpMethod.GET, "/api/attendance/**")
                    .hasAnyRole("SUPER_ADMIN", "ADMIN", "HOD", "TEACHER", "STUDENT")
                .requestMatchers(HttpMethod.POST, "/api/attendance/session")
                    .hasAnyRole("SUPER_ADMIN", "ADMIN", "HOD", "TEACHER")
                .requestMatchers(HttpMethod.POST, "/api/attendance/mark")
                    .hasAnyRole("SUPER_ADMIN", "ADMIN", "HOD", "TEACHER")
                .requestMatchers(HttpMethod.DELETE, "/api/attendance/**")
                    .hasAnyRole("SUPER_ADMIN", "ADMIN")

                // ── Marks ─────────────────────────────────────────────────────
                .requestMatchers(HttpMethod.GET, "/api/marks/**")
                    .hasAnyRole("SUPER_ADMIN", "ADMIN", "HOD", "TEACHER", "STUDENT")
                .requestMatchers(HttpMethod.POST, "/api/marks/**")
                    .hasAnyRole("SUPER_ADMIN", "ADMIN", "HOD", "TEACHER")
                .requestMatchers(HttpMethod.PUT, "/api/marks/**")
                    .hasAnyRole("SUPER_ADMIN", "ADMIN", "HOD", "TEACHER")
                .requestMatchers(HttpMethod.DELETE, "/api/marks/**")
                    .hasAnyRole("SUPER_ADMIN", "ADMIN", "HOD")

                // ── Devices ───────────────────────────────────────────────────
                .requestMatchers(HttpMethod.GET, "/api/devices/**")
                    .hasAnyRole("SUPER_ADMIN", "ADMIN", "HOD", "TEACHER", "STUDENT")
                .requestMatchers("/api/devices/*/control")
                    .hasAnyRole("SUPER_ADMIN", "ADMIN", "HOD", "TEACHER")
                .requestMatchers("/api/devices/**")
                    .hasAnyRole("SUPER_ADMIN", "ADMIN")

                // ── Environment ───────────────────────────────────────────────
                .requestMatchers(HttpMethod.GET, "/api/environment/**")
                    .hasAnyRole("SUPER_ADMIN", "ADMIN", "HOD", "TEACHER", "STUDENT")

                // ── Notifications ─────────────────────────────────────────────
                .requestMatchers("/api/notifications/**")
                    .hasAnyRole("SUPER_ADMIN", "ADMIN", "HOD", "TEACHER", "STUDENT")

                // ── Dashboard ─────────────────────────────────────────────────
                .requestMatchers("/api/dashboard/**")
                    .hasAnyRole("SUPER_ADMIN", "ADMIN", "HOD", "TEACHER", "STUDENT")

                // ── AI ────────────────────────────────────────────────────────
                    .requestMatchers("/api/ai/**")
                    .authenticated()

                // ── Live Bus Tracking: Strictly restricted to STUDENT role ──
                .requestMatchers("/api/bus-tracking/**")
                    .hasRole("STUDENT")

                // ── Audit Logs (admin only) ────────────────────────────────────────────────
                .requestMatchers("/api/audit/**", "/api/audit-logs/**")
                    .hasAnyRole("SUPER_ADMIN", "ADMIN")

                // ── Students: TEACHER can create + edit (Phase 3 RBAC) ────────────────
                // Note: fine-grained authz done in StudentController via RoleValidator

                // ── Any other /api endpoint requires authentication ───────────
                .requestMatchers("/api/**").authenticated()

                // ── All other requests (frontend SPA client routes) are public ─
                .anyRequest().permitAll()
            )
            .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public AuthenticationManager authenticationManager(
            AuthenticationConfiguration configuration) throws Exception {
        return configuration.getAuthenticationManager();
    }
}
