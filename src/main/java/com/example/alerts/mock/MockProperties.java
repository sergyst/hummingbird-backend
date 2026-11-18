package com.example.alerts.mock;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("hummingbird.mock")
public record MockProperties(
        boolean enabled,
        String clientId,
        String clientSecret,
        String apiVersion,
        long tokenTtlSeconds,
        String webhookUrl,
        String webhookSecret,
        boolean webhooksEnabled,
        Duration processDelay,
        Duration reviewDelay,
        Duration decisionDelay,
        Duration filingDelay,
        int rateLimitEvery) {

    /** Where the mock's endpoints live inside the backend; the backend's base-url points here. */
    public static final String BASE_PATH = "/hummingbird-mock";

    public boolean anyCredentials() {
        return (clientId == null || clientId.isBlank()) && (clientSecret == null || clientSecret.isBlank());
    }
}
