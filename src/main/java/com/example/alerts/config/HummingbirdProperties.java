package com.example.alerts.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "hummingbird")
public record HummingbirdProperties(
        @DefaultValue("https://api.sandbox.hummingbird.co") String baseUrl,
        @DefaultValue("2026-02-05") String apiVersion,
        @DefaultValue("") String clientId,
        @DefaultValue("") String clientSecret,
        @DefaultValue("") String webhookSecret,
        @DefaultValue("false") boolean webhookAllowUnsigned,
        @DefaultValue("PT30S") Duration pollInterval) {

    public boolean hasCredentials() {
        return !clientId.isBlank() && !clientSecret.isBlank();
    }

    public boolean sandbox() {
        return baseUrl.contains("sandbox");
    }
}
