package com.example.alerts.client;

import com.example.alerts.config.HummingbirdProperties;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.json.JsonParserFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * Thin client for the parts of the Hummingbird API this app uses.
 *
 * <ul>
 *   <li>OAuth2 client-credentials token, cached until shortly before expiry (docs: ~1 hour)</li>
 *   <li>Required {@code apiVersion} query parameter on every call</li>
 *   <li>Honours {@code Retry-After} on HTTP 429</li>
 * </ul>
 */
@Component
public class HummingbirdClient {

    private static final Logger log = LoggerFactory.getLogger(HummingbirdClient.class);
    private static final ParameterizedTypeReference<Map<String, Object>> MAP = new ParameterizedTypeReference<>() {};
    private static final int MAX_ATTEMPTS = 3;

    private final HummingbirdProperties props;
    private final RestClient http;

    private String accessToken;
    private Instant accessTokenExpiry = Instant.EPOCH;

    public HummingbirdClient(HummingbirdProperties props) {
        this.props = props;
        // Follow redirects: POST /alerts/id_lookup answers 303 -> GET /alerts/{token}
        HttpClient jdk = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(jdk);
        factory.setReadTimeout(Duration.ofSeconds(30));
        // Spring Boot 4 no longer auto-configures RestClient.Builder with the web starter, so build it directly.
        this.http = RestClient.builder().baseUrl(props.baseUrl()).requestFactory(factory).build();
    }

    // ---------------------------------------------------------------- alerts

    /** POST /alert_imports - returns {success, alerts:[{token,id}]}. {@code token} is the processing token. */
    public Map<String, Object> upsertAlertImport(Map<String, Object> payload) {
        return call(HttpMethod.POST, "/alert_imports", payload, true);
    }

    /** GET /alert_imports/{token} - returns {success, alert:{token,id,state,case_token,review_token,processed_at,error}}. */
    public Map<String, Object> getAlertImport(String processingToken) {
        return call(HttpMethod.GET, "/alert_imports/{token}", null, true, processingToken);
    }

    /** POST /alerts/id_lookup (303 -> GET /alerts/{token}) - returns {case_alert:{outcome, lifecycle_events,...}}. */
    public Map<String, Object> lookupCaseAlert(String externalAlertId) {
        return call(HttpMethod.POST, "/alerts/id_lookup",
                Map.of("case_alert", Map.of("alert_id", externalAlertId)), true);
    }

    // ----------------------------------------------------------------- cases

    /**
     * GET /cases - lists cases. {@code query} is passed through as-is (e.g. {@code first=25},
     * {@code workflow=triage}, and the paging cursor Hummingbird returned); blank values are dropped.
     */
    public Map<String, Object> listCases(Map<String, String> query) {
        return call(HttpMethod.GET, "/cases", query, null, true);
    }

    /** GET /cases/{token} - a single case. */
    public Map<String, Object> getCase(String caseToken) {
        return call(HttpMethod.GET, "/cases/{token}", Map.of(), null, true, caseToken);
    }

    /** GET /reviews - lists reviews, e.g. {@code case_token=...&page_size=25}. */
    public Map<String, Object> listReviews(Map<String, String> query) {
        return call(HttpMethod.GET, "/reviews", query, null, true);
    }

    // ------------------------------------------------------------------ core

    private Map<String, Object> call(HttpMethod method, String path, Object body, boolean authenticated, Object... vars) {
        return call(method, path, Map.of(), body, authenticated, vars);
    }

    private Map<String, Object> call(HttpMethod method, String path, Map<String, String> query, Object body,
                                     boolean authenticated, Object... vars) {
        for (int attempt = 1; ; attempt++) {
            try {
                RestClient.RequestBodySpec spec = http.method(method)
                        .uri(b -> {
                            b.path(path);
                            query.forEach((k, v) -> {
                                if (v != null && !v.isBlank() && !"apiVersion".equals(k)) {
                                    b.queryParam(k, v);
                                }
                            });
                            return b.queryParam("apiVersion", props.apiVersion()).build(vars);
                        })
                        .accept(MediaType.APPLICATION_JSON);
                if (authenticated) {
                    spec = spec.header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken());
                }
                if (body != null) {
                    spec = spec.contentType(MediaType.APPLICATION_JSON).body(body);
                }
                return spec.retrieve().body(MAP);
            } catch (HttpClientErrorException.TooManyRequests e) {
                if (attempt >= MAX_ATTEMPTS) {
                    throw translate(e);
                }
                long wait = retryAfterSeconds(e);
                log.warn("Hummingbird rate limit hit, retrying in {}s (attempt {}/{})", wait, attempt, MAX_ATTEMPTS);
                sleep(wait);
            } catch (RestClientResponseException e) {
                throw translate(e);
            } catch (RestClientException e) {
                throw new HummingbirdApiException(502, "UNREACHABLE",
                        "Could not reach Hummingbird: " + e.getMessage(), null);
            }
        }
    }

    private synchronized String accessToken() {
        if (accessToken != null && Instant.now().isBefore(accessTokenExpiry.minusSeconds(60))) {
            return accessToken;
        }
        if (!props.hasCredentials()) {
            throw new HummingbirdApiException(503, "NOT_CONFIGURED",
                    "Hummingbird credentials are not configured. Set HUMMINGBIRD_CLIENT_ID and HUMMINGBIRD_CLIENT_SECRET.",
                    null);
        }
        try {
            Map<String, Object> res = http.post()
                    .uri("/oauth/token")
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(Map.of(
                            "grant_type", "client_credentials",
                            "client_id", props.clientId(),
                            "client_secret", props.clientSecret()))
                    .retrieve()
                    .body(MAP);
            if (res == null || res.get("access_token") == null) {
                throw new HummingbirdApiException(502, "BAD_TOKEN_RESPONSE", "Token endpoint returned no access_token", null);
            }
            accessToken = String.valueOf(res.get("access_token"));
            long ttl = res.get("expires_in") instanceof Number n ? n.longValue() : 3600L;
            accessTokenExpiry = Instant.now().plusSeconds(ttl);
            log.info("Obtained Hummingbird access token (valid {}s)", ttl);
            return accessToken;
        } catch (RestClientResponseException e) {
            throw translate(e);
        }
    }

    // --------------------------------------------------------------- helpers

    private HummingbirdApiException translate(RestClientResponseException e) {
        String code = "HTTP_" + e.getStatusCode().value();
        String message = e.getStatusText().isBlank() ? "Hummingbird request failed" : e.getStatusText();
        String traceId = null;
        try {
            String raw = e.getResponseBodyAsString(StandardCharsets.UTF_8);
            if (raw != null && raw.startsWith("{")) {
                Map<String, Object> json = JsonParserFactory.getJsonParser().parseMap(raw);
                if (json.get("error") instanceof Map<?, ?> err) {
                    if (err.get("code") != null) code = String.valueOf(err.get("code"));
                    if (err.get("message") != null) message = String.valueOf(err.get("message"));
                    if (err.get("param") != null && !String.valueOf(err.get("param")).isBlank()) {
                        message += " (" + err.get("param") + ")";
                    }
                }
                if (json.get("trace_id") != null) traceId = String.valueOf(json.get("trace_id"));
            }
        } catch (RuntimeException ignored) {
            // keep the generic message
        }
        return new HummingbirdApiException(e.getStatusCode().value(), code, message, traceId);
    }

    private static long retryAfterSeconds(HttpClientErrorException e) {
        String header = e.getResponseHeaders() == null ? null : e.getResponseHeaders().getFirst(HttpHeaders.RETRY_AFTER);
        try {
            return Math.min(Math.max(Long.parseLong(header == null ? "" : header.trim()), 1), 30);
        } catch (NumberFormatException ex) {
            return 2;
        }
    }

    private static void sleep(long seconds) {
        try {
            Thread.sleep(seconds * 1000);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }
}
