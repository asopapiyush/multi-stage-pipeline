package com.pipeline.repository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;

import javax.sql.DataSource;
import java.sql.*;
import java.util.Optional;

@Repository
public class UserRepository {
    private static final Logger log = LoggerFactory.getLogger(UserRepository.class);

    private final DataSource dataSource;

    public UserRepository(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    private Connection getConnection() throws SQLException {
        return dataSource.getConnection();
    }

    public Optional<String> findPasswordHashByUsername(String username) {
        String sql = "SELECT password_hash FROM users WHERE username = ?";

        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setString(1, username);
            ResultSet rs = ps.executeQuery();

            if (rs.next()) {
                return Optional.of(rs.getString("password_hash"));
            }

        } catch (SQLException e) {
            log.error("Failed to look up user: {}", username, e);
        }

        return Optional.empty();
    }

    public boolean existsByUsername(String username) {
        String sql = "SELECT 1 FROM users WHERE username = ?";

        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setString(1, username);
            ResultSet rs = ps.executeQuery();
            return rs.next();

        } catch (SQLException e) {
            log.error("Failed to check user existence: {}", username, e);
            return false;
        }
    }

    public void upsertUser(String username, String passwordHash, long createdAt) {
        String sql = "INSERT INTO users (username, password_hash, created_at) VALUES (?, ?, ?) " +
            "ON CONFLICT (username) DO UPDATE SET password_hash = EXCLUDED.password_hash";

        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setString(1, username);
            ps.setString(2, passwordHash);
            ps.setLong(3, createdAt);

            ps.executeUpdate();
            log.info("Upserted user: {}", username);

        } catch (SQLException e) {
            log.error("Failed to upsert user: {}", username, e);
        }
    }
}
