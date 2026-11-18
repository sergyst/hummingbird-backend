package com.example.alerts.client;

import com.example.alerts.client.model.AccessTokenResponse;
import com.example.alerts.client.model.AlertImportStatusResponse;
import com.example.alerts.client.model.AlertImportsCreated;
import com.example.alerts.client.model.ApiErrorResponse;
import com.example.alerts.client.model.CaseAlertResponse;
import com.example.alerts.client.model.CaseDetailResponse;
import com.example.alerts.client.model.CaseList;
import com.example.alerts.client.model.ReviewList;
import com.example.alerts.config.HummingbirdProperties;
import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 *   <li>On HTTP 401 drops the cached token and retries once with a fresh one (revoked / rotated key)</li>
 *   <li>Typed responses (records in {@code client.model}) that model only the fields this app uses and ignore the
 *       rest, so Hummingbird can add fields without breaking us</li>
 * </ul>
 *
 * The {@code ...Json} methods at the bottom return Hummingbird's JSON untouched. They exist only for the console's
 * pass-through endpoints; application logic uses the typed methods.
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

    /**
     * POST /alert_imports. The request body is still a plain map (see {@code AlertService.buildPayload}); only
     * responses are typed. {@code token} in the response is the processing token.
     */
    public AlertImportsCreated upsertAlertImport(Map<String, Object> payload) {
        return call(HttpMethod.POST, "/alert_imports", Map.of(), payload, true, body(AlertImportsCreated.class));
    }

    /** GET /alert_imports/{token} - processing state plus case_token / review_token once processed. */
    public AlertImportStatusResponse getAlertImport(String processingToken) {
        return call(HttpMethod.GET, "/alert_imports/{token}", Map.of(), null, true,
                body(AlertImportStatusResponse.class), processingToken);
    }

    /** POST /alerts/id_lookup (303 -> GET /alerts/{token}) - the case alert's outcome and lifecycle events. */
    public CaseAlertResponse lookupCaseAlert(String externalAlertId) {
        return call(HttpMethod.POST, "/alerts/id_lookup", Map.of(),
                Map.of("case_alert", Map.of("alert_id", externalAlertId)), true, body(CaseAlertResponse.class));
    }

    // ----------------------------------------------------------------- cases

    /**
     * GET /cases - lists cases. {@code query} is passed through as-is (e.g. {@code first=25},
     * {@code workflow=triage}, and the paging cursor Hummingbird returned); blank values are dropped.
     */
    public CaseList listCases(Map<String, String> query) {
        return call(HttpMethod.GET, "/cases", query, null, true, body(CaseList.class));
    }

    /** GET /cases/{token} - a single case with its reviews. */
    public CaseDetailResponse getCase(String caseToken) {
        return call(HttpMethod.GET, "/cases/{token}", Map.of(), null, true, body(CaseDetailResponse.class), caseToken);
    }

    /** GET /reviews - lists reviews, e.g. {@code case_token=...&page_size=25}; page with {@code next_page_token}. */
    public ReviewList listReviews(Map<String, String> query) {
        return call(HttpMethod.GET, "/reviews", query, null, true, body(ReviewList.class));
    }

    // ------------------------------------------- raw JSON (console pass-through only)

    /** GET /cases, unmodified. */
    public Map<String, Object> listCasesJson(Map<String, String> query) {
        return call(HttpMethod.GET, "/cases", query, null, true, rawBody());
    }

    /** GET /cases/{token}, unmodified. */
    public Map<String, Object> getCaseJson(String caseToken) {
        return call(HttpMethod.GET, "/cases/{token}", Map.of(), null, true, rawBody(), caseToken);
    }

    /** GET /reviews, unmodified. */
    public Map<String, Object> listReviewsJson(Map<String, String> query) {
        return call(HttpMethod.GET, "/reviews", query, null, true, rawBody());
    }

    // ------------------------------------------------------------------ core

    private static <T> Function<RestClient.ResponseSpec, T> body(Class<T> type) {
        return spec -> spec.body(type);
    }

    private static Function<RestClient.ResponseSpec, Map<String, Object>> rawBody() {
        return spec -> spec.body(MAP);
    }

    private <T> T call(HttpMethod method, String path, Map<String, String> query, Object body, boolean authenticated,
                       Function<RestClient.ResponseSpec, T> read, Object... vars) {
        boolean tokenRenewed = false;
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
                return read.apply(spec.retrieve());
            } catch (HttpClientErrorException.TooManyRequests e) {
                if (attempt >= MAX_ATTEMPTS) {
                    throw translate(e);
                }
                long wait = retryAfterSeconds(e);
                log.warn("Hummingbird rate limit hit, retrying in {}s (attempt {}/{})", wait, attempt, MAX_ATTEMPTS);
                sleep(wait);
            } catch (HttpClientErrorException.Unauthorized e) {
                if (!authenticated || tokenRenewed) {
                    throw translate(e);
                }
                tokenRenewed = true; // the cached token may have been revoked: get a new one and try once more
                log.info("Hummingbird rejected the access token; requesting a new one");
                invalidateToken();
            } catch (RestClientResponseException e) {
                throw translate(e);
            } catch (RestClientException e) {
                throw new HummingbirdApiException(502, "UNREACHABLE",
                        "Could not reach Hummingbird: " + e.getMessage(), null);
            }
        }
    }

    private synchronized void invalidateToken() {
        accessToken = null;
        accessTokenExpiry = Instant.EPOCH;
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
            AccessTokenResponse res = http.post()
                    .uri("/oauth/token")
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(Map.of(
                            "grant_type", "client_credentials",
                            "client_id", props.clientId(),
                            "client_secret", props.clientSecret()))
                    .retrieve()
                    .body(AccessTokenResponse.class);
            if (res == null || res.accessToken() == null) {
                throw new HummingbirdApiException(502, "BAD_TOKEN_RESPONSE", "Token endpoint returned no access_token", null);
            }
            accessToken = res.accessToken();
            long ttl = res.expiresIn() != null ? res.expiresIn() : 3600L;
            accessTokenExpiry = Instant.now().plusSeconds(ttl);
            log.info("Obtained Hummingbird access token (valid {}s)", ttl);
            return accessToken;
        } catch (RestClientResponseException e) {
            throw translate(e);
        } catch (RestClientException e) {
            throw new HummingbirdApiException(502, "UNREACHABLE", "Could not reach Hummingbird: " + e.getMessage(), null);
        }
    }

    // --------------------------------------------------------------- helpers

    private HummingbirdApiException translate(RestClientResponseException e) {
        String code = "HTTP_" + e.getStatusCode().value();
        String message = e.getStatusText().isBlank() ? "Hummingbird request failed" : e.getStatusText();
        String traceId = null;
        try {
            ApiErrorResponse body = e.getResponseBodyAs(ApiErrorResponse.class);
            if (body != null) {
                // documented error body: {success:false, error:{type, message}}
                ApiErrorResponse.ApiError err = body.error();
                if (err != null) {
                    if (err.code() != null) code = err.code();
                    else if (err.type() != null) code = err.type();
                    if (err.message() != null) message = err.message();
                    if (err.param() != null && !err.param().isBlank()) {
                        message += " (" + err.param() + ")";
                    }
                }
                traceId = body.traceId();
            }
        } catch (RuntimeException ignored) {
            // not JSON, or not our error shape: keep the generic message
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
