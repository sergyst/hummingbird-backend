package com.example.alerts.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** Loads the real application*.yml files and binds {@link HummingbirdProperties}, with and without the mock profile. */
class MockProfileTest {

    @EnableConfigurationProperties(HummingbirdProperties.class)
    static class Config {
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withUserConfiguration(Config.class);

    @Test
    void mockProfilePointsAtTheMockWithItsCredentials() {
        runner.withPropertyValues("spring.profiles.active=mock").run(ctx -> {
            HummingbirdProperties p = ctx.getBean(HummingbirdProperties.class);
            assertEquals("http://localhost:8089", p.baseUrl());
            assertEquals("local", p.environment());
            assertEquals("mock-client", p.clientId());
            assertEquals("mock-secret", p.clientSecret());
            assertEquals("mock-webhook-secret", p.webhookSecret());
            assertTrue(p.hasCredentials());
        });
    }

    @Test
    void mockProfileValuesCanStillBeOverridden() {
        runner.withPropertyValues("spring.profiles.active=mock", "hummingbird.base-url=http://localhost:9000").run(ctx ->
                assertEquals("http://localhost:9000", ctx.getBean(HummingbirdProperties.class).baseUrl()));
    }

    @Test
    void withoutTheProfileTheSandboxIsStillTheDefault() {
        runner.run(ctx -> {
            HummingbirdProperties p = ctx.getBean(HummingbirdProperties.class);
            assertEquals("https://api.sandbox.hummingbird.co", p.baseUrl());
            assertEquals("sandbox", p.environment());
            assertFalse(p.webhookSecret().equals("mock-webhook-secret"), "mock secrets must not leak into the default profile");
        });
    }
}
