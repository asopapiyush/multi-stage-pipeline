package com.pipeline.repository;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.logging.Logger;

/**
 * Minimal DataSource wrapping DriverManager for tests that construct JobRepository/
 * UserRepository directly rather than going through Spring's autoconfigured Hikari bean.
 * Requires a running Postgres instance — see docker-compose.yml's `postgres` service, or
 * set TEST_DATABASE_URL to point at any reachable Postgres.
 */
public class TestDataSourceFactory {

    private static final String DEFAULT_URL = "jdbc:postgresql://localhost:5432/pipeline_test";
    private static final String DEFAULT_USER = "pipeline";
    private static final String DEFAULT_PASSWORD = "pipeline";

    public static DataSource create() {
        String url = System.getenv().getOrDefault("TEST_DATABASE_URL", DEFAULT_URL);
        String user = System.getenv().getOrDefault("SPRING_DATASOURCE_USERNAME", DEFAULT_USER);
        String password = System.getenv().getOrDefault("SPRING_DATASOURCE_PASSWORD", DEFAULT_PASSWORD);

        return new DataSource() {
            @Override
            public Connection getConnection() throws SQLException {
                return DriverManager.getConnection(url, user, password);
            }

            @Override
            public Connection getConnection(String username, String password) throws SQLException {
                return DriverManager.getConnection(url, username, password);
            }

            @Override
            public PrintWriter getLogWriter() { return null; }

            @Override
            public void setLogWriter(PrintWriter out) { }

            @Override
            public void setLoginTimeout(int seconds) { }

            @Override
            public int getLoginTimeout() { return 0; }

            @Override
            public Logger getParentLogger() {
                return Logger.getLogger("global");
            }

            @Override
            public <T> T unwrap(Class<T> iface) {
                return null;
            }

            @Override
            public boolean isWrapperFor(Class<?> iface) {
                return false;
            }
        };
    }
}
