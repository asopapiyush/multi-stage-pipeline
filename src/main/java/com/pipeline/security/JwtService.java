package com.pipeline.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

@Component
public class JwtService {

    private final SecretKey signingKey;
    private final long expirationMs;

    public JwtService(
        @Value("${jwt.secret}") String secret,
        @Value("${jwt.expiration-ms:28800000}") long expirationMs
    ) {
        this.signingKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.expirationMs = expirationMs;
    }

    public String generateToken(String username) {
        Date now = new Date();
        Date expiry = new Date(now.getTime() + expirationMs);

        return Jwts.builder()
            .subject(username)
            .issuedAt(now)
            .expiration(expiry)
            .signWith(signingKey, Jwts.SIG.HS256)
            .compact();
    }

    public long getExpirationMs() {
        return expirationMs;
    }

    /**
     * Validates the token and returns the username (subject).
     * Throws JwtException (or a subclass) if the token is missing, malformed, expired,
     * or has an invalid signature — callers should catch and treat as unauthenticated.
     */
    public String validateAndGetUsername(String token) throws JwtException {
        Claims claims = Jwts.parser()
            .verifyWith(signingKey)
            .build()
            .parseSignedClaims(token)
            .getPayload();

        return claims.getSubject();
    }
}
