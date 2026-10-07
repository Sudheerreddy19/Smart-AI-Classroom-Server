package com.finalYear.smartClassRoom.service.impl;

import com.finalYear.smartClassRoom.dto.request.LoginRequest;
import com.finalYear.smartClassRoom.dto.request.RefreshTokenRequest;
import com.finalYear.smartClassRoom.dto.request.RegisterRequest;
import com.finalYear.smartClassRoom.dto.response.AuthResponse;
import com.finalYear.smartClassRoom.entity.Department;
import com.finalYear.smartClassRoom.entity.RefreshToken;
import com.finalYear.smartClassRoom.entity.Student;
import com.finalYear.smartClassRoom.entity.User;
import com.finalYear.smartClassRoom.exception.*;
import com.finalYear.smartClassRoom.repository.*;
import com.finalYear.smartClassRoom.config.JwtService;
import com.finalYear.smartClassRoom.service.AuditLogService;
import com.finalYear.smartClassRoom.service.AuthService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
@Transactional
public class AuthServiceImpl implements AuthService {

    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final AuthenticationManager authenticationManager;
    private final AuditLogService auditLogService;
    private final StudentRepository studentRepository;
    private final DepartmentRepository departmentRepository;

    private static final int MAX_FAILED_ATTEMPTS = 5;

    @Value("${app.jwt.refresh-token-expiration}")
    private long refreshTokenExpiration;

    @Override
    public AuthResponse login(LoginRequest request) {

        User user = userRepository.findByEmail(request.getEmail())
                .orElseThrow(() -> new BadCredentialsException("Invalid email or password"));

        if (user.isAccountLocked()) {
            throw new LockedException("Account is locked due to too many failed login attempts.");
        }

        try {
            authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(request.getEmail(), request.getPassword()));
        } catch (BadCredentialsException ex) {
            int attempts = user.getFailedLoginAttempts() + 1;
            user.setFailedLoginAttempts(attempts);
            if (attempts >= MAX_FAILED_ATTEMPTS) {
                user.setAccountLocked(true);
                log.warn("Account locked for {}", user.getEmail());
            }
            userRepository.save(user);
            auditLogService.logFailure("LOGIN_FAILED", "Failed login for: " + user.getEmail());
            throw ex;
        }

        user.setFailedLoginAttempts(0);
        user.setLastLogin(LocalDateTime.now());
        userRepository.save(user);

        String accessToken  = jwtService.generateAccessToken(user);
        String refreshToken = createRefreshToken(user);
        auditLogService.log("LOGIN", "User", user.getId(), "Login successful: " + user.getEmail());

        return buildAuthResponse(user, accessToken, refreshToken);
    }

    @Override
    public AuthResponse register(RegisterRequest request) {

        if (userRepository.existsByEmail(request.getEmail())) {
            throw new DuplicateResourceException("Email already registered: " + request.getEmail());
        }

        User user = User.builder()
                .firstName(request.getFirstName())
                .lastName(request.getLastName())
                .email(request.getEmail())
                .password(passwordEncoder.encode(request.getPassword()))
                .role(User.Role.STUDENT)   // always STUDENT — public registration only
                .phone(request.getPhone())
                .enabled(true)
                .emailVerified(false)
                .build();

        user = userRepository.save(user);

        // Create Student profile and link department if provided
        Department department = null;
        if (request.getDepartmentId() != null) {
            department = departmentRepository.findById(request.getDepartmentId())
                    .orElseThrow(() -> new ResourceNotFoundException(
                            "Department", request.getDepartmentId()));
        }

        // Auto-generate a temporary roll number; admin can update it later
        String tempRollNumber = "TEMP-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();

        Student student = Student.builder()
                .user(user)
                .rollNumber(tempRollNumber)
                .department(department)
                .active(true)
                .build();

        studentRepository.save(student);

        log.info("New student registered: {} (dept={})", user.getEmail(),
                department != null ? department.getName() : "none");
        auditLogService.log("REGISTER", "User", user.getId(), "Student registered: " + user.getEmail());

        String accessToken  = jwtService.generateAccessToken(user);
        String refreshToken = createRefreshToken(user);

        return buildAuthResponse(user, accessToken, refreshToken);
    }

    @Override
    public AuthResponse refreshToken(RefreshTokenRequest request) {

        RefreshToken token = refreshTokenRepository
                .findByToken(request.getRefreshToken())
                .orElseThrow(() ->
                        new TokenException("Refresh token not found"));

        if (token.isRevoked()) {
            throw new TokenException("Refresh token has been revoked");
        }

        if (token.getExpiryDate().isBefore(Instant.now())) {
            refreshTokenRepository.delete(token);
            throw new TokenException(
                    "Refresh token expired. Please login again."
            );
        }

        User user = token.getUser();

        String newAccessToken = jwtService.generateAccessToken(user);
        String newRefreshToken = createRefreshToken(user);

        token.setRevoked(true);
        refreshTokenRepository.save(token);

        return buildAuthResponse(user, newAccessToken, newRefreshToken);
    }

    @Override
    public void logout(String refreshToken) {
        refreshTokenRepository.findByToken(refreshToken)
                .ifPresent(token -> {
                    token.setRevoked(true);
                    refreshTokenRepository.save(token);
                });
    }

    @Override
    public void changePassword(String email, String oldPassword, String newPassword) {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
        if (!passwordEncoder.matches(oldPassword, user.getPassword())) {
            throw new BadRequestException("Current password is incorrect.");
        }
        if (newPassword.length() < 6) {
            throw new BadRequestException("New password must be at least 6 characters.");
        }
        user.setPassword(passwordEncoder.encode(newPassword));
        userRepository.save(user);
        refreshTokenRepository.revokeAllUserTokens(user);
        auditLogService.log("CHANGE_PASSWORD", "User", user.getId(), "Password changed: " + email);
    }

    private String createRefreshToken(User user) {

        refreshTokenRepository.revokeAllUserTokens(user);

        String tokenValue = jwtService.generateRefreshToken(user);

        RefreshToken refreshToken = RefreshToken.builder()
                .token(tokenValue)
                .user(user)
                .expiryDate(
                        Instant.now().plusMillis(refreshTokenExpiration)
                )
                .revoked(false)
                .build();

        refreshTokenRepository.save(refreshToken);

        return tokenValue;
    }

    private AuthResponse buildAuthResponse(User user, String accessToken, String refreshToken) {
        return AuthResponse.builder()
                .accessToken(accessToken)
                .refreshToken(refreshToken)
                .tokenType("Bearer")
                .userId(user.getId())
                .email(user.getEmail())
                .role(user.getRole())
                .firstName(user.getFirstName())
                .lastName(user.getLastName())
                .dashboardUrl(resolveDashboard(user.getRole()))
                .build();
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
}