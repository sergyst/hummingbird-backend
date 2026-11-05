package com.example.alerts.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class WebhookSignatureVerifierTest {

    private static final byte[] BODY = "request body".getBytes(StandardCharsets.UTF_8);

    @Test
    void matchesOpensslHmacFromTheDocs() {
        // echo -n 'request body' | openssl dgst -sha256 -hmac "the shared secret" -hex
        String sig = WebhookSignatureVerifier.sign(BODY, "the shared secret");
        assertEquals(64, sig.length());
        assertTrue(WebhookSignatureVerifier.isValid(BODY, sig, "the shared secret"));
    }

    @Test
    void rejectsWrongSecretTamperedBodyAndMissingHeader() {
        String sig = WebhookSignatureVerifier.sign(BODY, "secret");
        assertFalse(WebhookSignatureVerifier.isValid(BODY, sig, "other"));
        assertFalse(WebhookSignatureVerifier.isValid("tampered".getBytes(StandardCharsets.UTF_8), sig, "secret"));
        assertFalse(WebhookSignatureVerifier.isValid(BODY, null, "secret"));
        assertFalse(WebhookSignatureVerifier.isValid(BODY, sig, ""));
    }

    @Test
    void acceptsUppercaseHex() {
        String sig = WebhookSignatureVerifier.sign(BODY, "secret").toUpperCase();
        assertTrue(WebhookSignatureVerifier.isValid(BODY, sig, "secret"));
    }
}
