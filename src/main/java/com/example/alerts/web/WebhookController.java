package com.example.alerts.web;

import com.example.alerts.config.HummingbirdProperties;
import com.example.alerts.security.WebhookSignatureVerifier;
import com.example.alerts.service.AlertService;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.json.JsonParserFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Receives Hummingbird webhooks. Register {@code https://<public-host>/api/webhooks/hummingbird}
 * under Settings > Organization Settings > Developers > Webhooks and select the
 * "Alert Processed" and "Alert Stuck" events.
 */
@RestController
@RequestMapping("/api/webhooks")
public class WebhookController {

    private static final Logger log = LoggerFactory.getLogger(WebhookController.class);

    private final AlertService service;
    private final HummingbirdProperties props;

    public WebhookController(AlertService service, HummingbirdProperties props) {
        this.service = service;
        this.props = props;
    }

    @PostMapping("/hummingbird")
    public ResponseEntity<Void> receive(@RequestBody byte[] rawBody,
                                        @RequestHeader(value = "X-Signature", required = false) String signature) {
        // The signature covers the raw bytes, so verify before parsing anything.
        boolean valid = WebhookSignatureVerifier.isValid(rawBody, signature, props.webhookSecret());
        if (!valid && !props.webhookAllowUnsigned()) {
            log.warn("Rejected webhook: missing/invalid X-Signature (or HUMMINGBIRD_WEBHOOK_SECRET not set)");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        Map<String, Object> message;
        try {
            message = JsonParserFactory.getJsonParser().parseMap(new String(rawBody, StandardCharsets.UTF_8));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().build();
        }

        String type = String.valueOf(message.get("type"));
        if (message.get("data") instanceof Map<?, ?> data) {
            @SuppressWarnings("unchecked")
            Map<String, Object> typed = (Map<String, Object>) data;
            service.applyWebhook(type, typed);
        }
        // Always answer 2xx quickly once validated, otherwise Hummingbird retries (15+ times).
        return ResponseEntity.ok().build();
    }
}
