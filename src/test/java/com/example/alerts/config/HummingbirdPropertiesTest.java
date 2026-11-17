package com.example.alerts.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class HummingbirdPropertiesTest {

    private static HummingbirdProperties with(String baseUrl) {
        return new HummingbirdProperties(baseUrl, "2026-02-05", "", "", "", false, Duration.ofSeconds(30), 4);
    }

    @Test
    void sandboxUrlIsSandbox() {
        HummingbirdProperties p = with("https://api.sandbox.hummingbird.co");
        assertEquals("sandbox", p.environment());
        assertTrue(p.sandbox());
    }

    @Test
    void productionUrlIsProduction() {
        HummingbirdProperties p = with("https://api.hummingbird.co");
        assertEquals("production", p.environment());
        assertFalse(p.sandbox());
    }

    @Test
    void localServersAreLocalAndCountAsNonProduction() {
        for (String url : new String[] {"http://localhost:8089", "http://127.0.0.1:8089", "http://[::1]:8089"}) {
            HummingbirdProperties p = with(url);
            assertEquals("local", p.environment(), url);
            assertTrue(p.sandbox(), "a local stand-in is never production: " + url);
        }
    }

    @Test
    void aHostThatMerelyContainsLocalhostIsNotLocal() {
        assertEquals("production", with("https://localhost.evil.example").environment());
    }
}
