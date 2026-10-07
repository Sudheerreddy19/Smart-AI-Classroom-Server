package com.finalYear.smartClassRoom.config;

import com.finalYear.smartClassRoom.entity.User;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

@Service
@Slf4j
public class JwtService {

    @Value("${app.jwt.secret}")
    private String jwtSecret;

    @Value("${app.jwt.access-token-expiration}")
    private long accessTokenExpiration;

    @Value("${app.jwt.refresh-token-expiration}")
    private long refreshTokenExpiration;

    // ── Token Generation ─────────────────────────────────────────────────────

    /**
     * Generate access token with role embedded as a claim.
     */
    public String generateAccessToken(UserDetails userDetails) {
        Map<String, Object> claims = new HashMap<>();
        if (userDetails instanceof User user) {
            claims.put("role",        user.getRole().name());
            claims.put("userId",      user.getId());
            claims.put("firstName",   user.getFirstName());
            claims.put("lastName",    user.getLastName());
            claims.put("permissions", resolvePermissions(user.getRole()));
            claims.put("dashboardUrl", resolveDashboard(user.getRole()));
        }
        return buildToken(claims, userDetails, accessTokenExpiration);
    }

    private String resolveDashboard(User.Role role) {
        return switch (role) {
            case SUPER_ADMIN -> "/super-admin/dashboard";
            case ADMIN       -> "/admin/dashboard";
            case HOD         -> "/hod/dashboard";
            case TEACHER     -> "/teacher/dashboard";
            case STUDENT     -> "/student/dashboard";
        };
    }

    private java.util.List<String> resolvePermissions(User.Role role) {
        return switch (role) {
            case SUPER_ADMIN -> java.util.List.of(
                "MANAGE_ADMINS", "MANAGE_HODS", "MANAGE_TEACHERS", "MANAGE_STUDENTS",
                "MANAGE_DEPARTMENTS", "MANAGE_SUBJECTS", "MANAGE_SEMESTERS",
                "MANAGE_CLASSROOMS", "MANAGE_DEVICES", "MANAGE_AI",
                "VIEW_REPORTS", "MANAGE_SETTINGS", "VIEW_AUDIT_LOGS", "DELETE_ANY");
            case ADMIN -> java.util.List.of(
                "MANAGE_TEACHERS", "MANAGE_STUDENTS", "MANAGE_HODS",
                "MANAGE_DEPARTMENTS", "MANAGE_SUBJECTS", "MANAGE_SEMESTERS",
                "MANAGE_CLASSROOMS", "MANAGE_DEVICES", "MANAGE_AI",
                "VIEW_REPORTS", "MANAGE_ATTENDANCE", "MANAGE_MARKS");
            case HOD -> java.util.List.of(
                "VIEW_DEPT_TEACHERS", "VIEW_DEPT_STUDENTS", "ASSIGN_SUBJECTS",
                "VIEW_ATTENDANCE", "VIEW_MARKS", "APPROVE_MARKS",
                "VIEW_DEPT_REPORTS", "MANAGE_DEPT_TIMETABLE");
            case TEACHER -> java.util.List.of(
                "VIEW_ASSIGNED_STUDENTS", "TAKE_ATTENDANCE", "ADD_MARKS",
                "UPDATE_MARKS", "VIEW_TIMETABLE", "UPLOAD_ASSIGNMENT",
                "GRADE_ASSIGNMENT", "CONTROL_CLASSROOM_DEVICES", "USE_AI");
            case STUDENT -> java.util.List.of(
                "VIEW_OWN_ATTENDANCE", "VIEW_OWN_MARKS", "VIEW_TIMETABLE",
                "SUBMIT_ASSIGNMENT", "USE_AI", "UPDATE_OWN_PROFILE",
                "VIEW_NOTIFICATIONS", "VIEW_ENVIRONMENT");
        };
    }

    public String generateRefreshToken(UserDetails userDetails) {
        return buildToken(new HashMap<>(), userDetails, refreshTokenExpiration);
    }

    private String buildToken(Map<String, Object> claims, UserDetails userDetails, long expiration) {
        return Jwts.builder()
                .claims(claims)
                .subject(userDetails.getUsername())
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + expiration))
                .signWith(getSigningKey())
                .compact();
    }

    // ── Token Validation ─────────────────────────────────────────────────────

    public boolean isTokenValid(String token, UserDetails userDetails) {
        final String username = extractUsername(token);
        return username.equals(userDetails.getUsername()) && !isTokenExpired(token);
    }

    public boolean isTokenExpired(String token) {
        return extractExpiration(token).before(new Date());
    }

    // ── Claim Extraction ─────────────────────────────────────────────────────

    public String extractUsername(String token) {
        return extractClaim(token, Claims::getSubject);
    }

    public Date extractExpiration(String token) {
        return extractClaim(token, Claims::getExpiration);
    }

    public String extractRole(String token) {
        return extractClaim(token, claims -> claims.get("role", String.class));
    }

    public Long extractUserId(String token) {
        return extractClaim(token, claims -> {
            Object id = claims.get("userId");
            if (id instanceof Long l)    return l;
            if (id instanceof Integer i) return i.longValue();
            if (id instanceof Number n)  return n.longValue();
            throw new IllegalArgumentException(
                "Unexpected userId claim type: " + (id == null ? "null" : id.getClass().getName()));
        });
    }

    public <T> T extractClaim(String token, Function<Claims, T> resolver) {
        return resolver.apply(extractAllClaims(token));
    }

    private Claims extractAllClaims(String token) {
        return Jwts.parser()
                .verifyWith(getSigningKey())
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    private SecretKey getSigningKey() {
        try {
            byte[] keyBytes;
            // 1. If it's a 32-byte hex string (64 chars) or 16-byte hex (32 chars)
            if (jwtSecret != null && jwtSecret.matches("^[0-9a-fA-F]{32,64}$")) {
                try {
                    keyBytes = io.jsonwebtoken.io.Decoders.BASE64.decode(jwtSecret);
                } catch (Exception ex) {
                    keyBytes = jwtSecret.getBytes(StandardCharsets.UTF_8);
                }
            } else {
                try {
                    keyBytes = Decoders.BASE64.decode(jwtSecret);
                } catch (Exception e) {
                    keyBytes = jwtSecret.getBytes(StandardCharsets.UTF_8);
                }
            }
            // HMAC-SHA256 requires at least 256 bits (32 bytes)
            if (keyBytes.length < 32) {
                java.security.MessageDigest sha256 = java.security.MessageDigest.getInstance("SHA-256");
                keyBytes = sha256.digest(jwtSecret.getBytes(StandardCharsets.UTF_8));
            }
            return Keys.hmacShaKeyFor(keyBytes);
        } catch (Exception e) {
            log.error("Error creating JWT signing key: {}", e.getMessage());
            // Fallback: SHA-256 hash of secret string guarantees 256 bits
            try {
                java.security.MessageDigest sha256 = java.security.MessageDigest.getInstance("SHA-256");
                byte[] hash = sha256.digest(jwtSecret != null ? jwtSecret.getBytes(StandardCharsets.UTF_8) : "default-key".getBytes(StandardCharsets.UTF_8));
                return Keys.hmacShaKeyFor(hash);
            } catch (Exception fatal) {
                throw new RuntimeException("Cannot initialize JWT signing key", fatal);
            }
        }
    }
}
