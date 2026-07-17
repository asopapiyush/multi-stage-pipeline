package com.pipeline.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * The static UI runs as its own service (locally: server.js on :3000; on Render: a
 * separate Docker web service), independent of this Spring Boot app. This CORS config
 * allows that origin to call the REST API; the WebSocket endpoint's allowed origins are
 * configured separately in WebSocketConfig. Both read the same app.allowed-origins
 * property so there's one place to update per environment (see ALLOWED_ORIGINS env var).
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Value("${app.allowed-origins:http://localhost:3000,http://localhost:8080}")
    private String allowedOrigins;

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
            .allowedOrigins(allowedOrigins.split(","))
            .allowedMethods("GET", "POST", "DELETE")
            .allowedHeaders("Content-Type", "Authorization")
            .allowCredentials(false)
            .maxAge(3600);
    }
}
