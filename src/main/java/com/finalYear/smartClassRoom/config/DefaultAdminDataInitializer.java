package com.finalYear.smartClassRoom.config;

import com.finalYear.smartClassRoom.entity.User;
import com.finalYear.smartClassRoom.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@Order(1)
@RequiredArgsConstructor
public class DefaultAdminDataInitializer implements CommandLineRunner {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    @Override
    public void run(String... args) {
        String[] emails = {
            "byreddy19sudheerreddy@gmail.com",
            "byreddy19sudheer@gmail.com"
        };

        for (String email : emails) {
            if (userRepository.findByEmail(email).isEmpty()) {
                User admin = User.builder()
                        .firstName("Sudheer")
                        .lastName("Reddy")
                        .email(email)
                        .password(passwordEncoder.encode("23FE1A0424@s"))
                        .role(User.Role.SUPER_ADMIN)
                        .enabled(true)
                        .accountLocked(false)
                        .failedLoginAttempts(0)
                        .build();

                userRepository.save(admin);
                log.info("Default SUPER_ADMIN user created: {}", email);
            } else {
                log.info("Default user {} already exists in database.", email);
            }
        }
    }
}
