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
        @DefaultValue("PT30S") Duration pollInterval,
        /** How many GET /cases/{token} calls the case summaries may have in flight at once. */
        @DefaultValue("4") int caseDetailConcurrency) {

    public boolean hasCredentials() {
        return !clientId.isBlank() && !clientSecret.isBlank();
    }

    /** True for anything that is not the production API (the sandbox, or a local stand-in such as hummingbird-mock). */
    public boolean sandbox() {
        return !"production".equals(environment());
    }

    /** {@code local} (localhost / 127.0.0.1 / ::1), {@code sandbox} (URL contains "sandbox") or {@code production}. */
    public String environment() {
        String host;
        try {
            host = java.net.URI.create(baseUrl).getHost();
        } catch (IllegalArgumentException e) {
            host = null;
        }
        if (host != null && (host.equals("localhost") || host.equals("127.0.0.1") || host.equals("[::1]") || host.equals("::1"))) {
            return "local";
        }
        return baseUrl.contains("sandbox") ? "sandbox" : "production";
    }
}
