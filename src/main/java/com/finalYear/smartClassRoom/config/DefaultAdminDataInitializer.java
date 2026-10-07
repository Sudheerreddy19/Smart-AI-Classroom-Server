package com.finalYear.smartClassRoom.config;

import com.finalYear.smartClassRoom.entity.User;
import com.finalYear.smartClassRoom.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.util.Locale;

@Slf4j
@Component
@Order(1)
@RequiredArgsConstructor
public class DefaultAdminDataInitializer implements CommandLineRunner {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    @Value("${ADMIN_EMAIL:}")
    private String adminEmail;

    @Value("${ADMIN_PASSWORD:}")
    private String adminPassword;

    @Value("${ADMIN_FIRST_NAME:Admin}")
    private String adminFirstName;

    @Value("${ADMIN_LAST_NAME:User}")
    private String adminLastName;

    @Override
    public void run(String... args) {
        if (adminEmail == null || adminEmail.isBlank() || adminPassword == null || adminPassword.isBlank()) {
            log.info("No ADMIN_EMAIL or ADMIN_PASSWORD environment variables found. Skipping automatic admin seeding.");
            return;
        }

        String normalizedEmail = adminEmail.trim().toLowerCase(Locale.ROOT);

        if (userRepository.findByEmail(normalizedEmail).isEmpty()) {
            User admin = User.builder()
                    .firstName(adminFirstName.trim())
                    .lastName(adminLastName.trim())
                    .email(normalizedEmail)
                    .password(passwordEncoder.encode(adminPassword.trim()))
                    .role(User.Role.SUPER_ADMIN)
                    .enabled(true)
                    .accountLocked(false)
                    .failedLoginAttempts(0)
                    .build();

            userRepository.save(admin);
            log.info("SUPER_ADMIN account seeded successfully from environment variables for email: {}", normalizedEmail);
        } else {
            log.info("Admin account {} already exists in the database. No seeding needed.", normalizedEmail);
        }
    }
}
