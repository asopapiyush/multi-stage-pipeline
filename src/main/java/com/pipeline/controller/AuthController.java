package com.pipeline.controller;

import com.pipeline.repository.UserRepository;
import com.pipeline.security.JwtService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private static final Logger log = LoggerFactory.getLogger(AuthController.class);

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    public AuthController(UserRepository userRepository, PasswordEncoder passwordEncoder, JwtService jwtService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
    }

    public record LoginRequest(String username, String password) {}

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody LoginRequest request) {
        if (request.username() == null || request.password() == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "Username and password are required"));
        }

        Optional<String> storedHash = userRepository.findPasswordHashByUsername(request.username());

        if (storedHash.isEmpty() || !passwordEncoder.matches(request.password(), storedHash.get())) {
            log.warn("Failed login attempt for username: {}", request.username());
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "Invalid credentials"));
        }

        String token = jwtService.generateToken(request.username());
        long expiresAt = System.currentTimeMillis() + jwtService.getExpirationMs();

        return ResponseEntity.ok(Map.of("token", token, "expiresAt", expiresAt));
    }
}
