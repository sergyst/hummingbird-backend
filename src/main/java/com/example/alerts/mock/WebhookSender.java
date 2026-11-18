package com.example.alerts.mock;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.util.HexFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Delivers webhooks like Hummingbird: JSON {@code {id,type,created_at,data}}, hex HMAC-SHA256 of the
 * raw body in {@code X-Signature}, retried on non-2xx / network errors.
 */
@ConditionalOnProperty(prefix = "hummingbird.mock", name = "enabled", havingValue = "true")
@Component
public class WebhookSender {

    private static final Logger log = LoggerFactory.getLogger(WebhookSender.class);
    private static final int MAX_ATTEMPTS = 5;

    private final MockProperties props;
    private final JsonMapper json = JsonMapper.builder().build();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final ScheduledExecutorService retries = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().daemon().factory());
    private final List<Map<String, Object>> deliveries = new ArrayList<>();

    private final Environment env;

    public WebhookSender(MockProperties props, Environment env) {
        this.props = props;
        this.env = env;
    }

    /** The configured webhook URL, else this backend's own webhook endpoint. */
    private String webhookUrl() {
        if (props.webhookUrl() != null && !props.webhookUrl().isBlank()) {
            return props.webhookUrl();
        }
        String port = env.getProperty("local.server.port", env.getProperty("server.port", "8080"));
        return "http://localhost:" + port + "/api/webhooks/hummingbird";
    }

    public void send(String type, Map<String, Object> data) {
        if (!props.webhooksEnabled()) {
            return;
        }
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("id", UUID.randomUUID().toString());
        message.put("type", type);
        message.put("created_at", Instant.now().toString());
        message.put("data", data);
        byte[] body = json.writeValueAsBytes(message);
        Map<String, Object> record = new ConcurrentHashMap<>();
        record.put("type", type);
        record.put("at", Instant.now().toString());
        record.put("status", "sending");
        record.put("attempts", 0);
        synchronized (deliveries) {
            deliveries.add(record);
            if (deliveries.size() > 200) deliveries.remove(0);
        }
        attempt(body, record, 1);
    }

    public List<Map<String, Object>> deliveries() {
        synchronized (deliveries) {
            return List.copyOf(deliveries);
        }
    }

    public void clear() {
        synchronized (deliveries) {
            deliveries.clear();
        }
    }

    private void attempt(byte[] body, Map<String, Object> record, int n) {
        HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(webhookUrl()))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body));
        if (props.webhookSecret() != null && !props.webhookSecret().isBlank()) {
            req.header("X-Signature", sign(body, props.webhookSecret()));
        }
        record.put("attempts", n);
        http.sendAsync(req.build(), HttpResponse.BodyHandlers.discarding()).whenComplete((res, err) -> {
            boolean ok = err == null && res.statusCode() / 100 == 2;
            if (ok) {
                record.put("status", "delivered");
                record.put("http_status", res.statusCode());
                return;
            }
            String why = err != null ? String.valueOf(err.getMessage()) : "HTTP " + res.statusCode();
            record.put("last_error", why);
            if (n >= MAX_ATTEMPTS) {
                record.put("status", "failed");
                log.warn("Webhook {} gave up after {} attempts: {}", record.get("type"), n, why);
                return;
            }
            record.put("status", "retrying");
            long delay = Math.min(30, 1L << n); // 2s, 4s, 8s, 16s
            retries.schedule(() -> attempt(body, record, n + 1), delay, TimeUnit.SECONDS);
        });
    }

    static String sign(byte[] body, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(body));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
