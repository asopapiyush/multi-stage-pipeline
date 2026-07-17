package com.pipeline.config;

import com.pipeline.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Seeds a single admin user from ADMIN_USERNAME/ADMIN_PASSWORD env vars on startup.
 * This is a single/few-user internal tool — there is deliberately no self-registration
 * endpoint. Rotating the password means changing the env var and letting the container
 * restart re-seed it (upsert overwrites the stored hash).
 */
@Component
public class AdminUserSeeder implements CommandLineRunner {
    private static final Logger log = LoggerFactory.getLogger(AdminUserSeeder.class);

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    @Value("${admin.username}")
    private String adminUsername;

    @Value("${admin.password}")
    private String adminPassword;

    public AdminUserSeeder(UserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    public void run(String... args) {
        if (adminPassword == null || adminPassword.isBlank()) {
            log.warn("ADMIN_PASSWORD is not set; skipping admin user seed. Login will fail until it is configured.");
            return;
        }

        String hash = passwordEncoder.encode(adminPassword);
        userRepository.upsertUser(adminUsername, hash, System.currentTimeMillis());
        log.info("Admin user '{}' seeded/updated", adminUsername);
    }
}
