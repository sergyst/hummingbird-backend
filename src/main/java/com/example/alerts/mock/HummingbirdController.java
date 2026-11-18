package com.example.alerts.mock;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import java.net.URI;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The Hummingbird endpoints the alerts backend calls. */
@ConditionalOnProperty(prefix = "hummingbird.mock", name = "enabled", havingValue = "true")
@RestController
@RequestMapping(MockProperties.BASE_PATH)
public class HummingbirdController {

    private final MockWorld world;
    private final MockProperties props;
    private final ApiInterceptor auth;

    public HummingbirdController(MockWorld world, MockProperties props, ApiInterceptor auth) {
        this.world = world;
        this.props = props;
        this.auth = auth;
    }

    // ----------------------------------------------------------------- oauth

    @PostMapping(value = "/oauth/token", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> tokenJson(@RequestBody Map<String, Object> body) {
        return issue(str(body.get("grant_type")), str(body.get("client_id")), str(body.get("client_secret")));
    }

    @PostMapping(value = "/oauth/token", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    public Map<String, Object> tokenForm(@RequestParam MultiValueMap<String, String> form) {
        return issue(form.getFirst("grant_type"), form.getFirst("client_id"), form.getFirst("client_secret"));
    }

    private Map<String, Object> issue(String grant, String id, String secret) {
        if (!"client_credentials".equals(grant)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "unsupported_grant_type", "grant_type must be client_credentials", "grant_type");
        }
        if (!props.anyCredentials() && (!eq(props.clientId(), id) || !eq(props.clientSecret(), secret))) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "invalid_client", "Invalid client_id or client_secret");
        }
        String token = MockWorld.token("tok");
        auth.register(token, Instant.now().plusSeconds(props.tokenTtlSeconds()));
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("access_token", token);
        res.put("token_type", "Bearer");
        res.put("expires_in", props.tokenTtlSeconds());
        return res;
    }

    // ---------------------------------------------------------------- alerts

    @PostMapping("/alert_imports")
    public Map<String, Object> importAlerts(@RequestBody Map<String, Object> body) {
        List<Map<String, Object>> alerts = world.importAlerts(body).stream()
                .map(a -> Map.<String, Object>of("token", a.processingToken, "id", a.externalId))
                .toList();
        return Map.of("success", true, "alerts", alerts);
    }

    @GetMapping("/alert_imports/{token}")
    public Map<String, Object> getImport(@PathVariable String token) {
        MockAlert a = world.byProcessingToken(token)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "not_found", "Alert import not found"));
        return Map.of("success", true, "alert", world.importView(a));
    }

    /** 303 to GET /alerts/{token}, like the real API. Not found until the alert has been processed. */
    @PostMapping("/alerts/id_lookup")
    @SuppressWarnings("unchecked")
    public ResponseEntity<Void> idLookup(@RequestBody Map<String, Object> body) {
        Object ca = body.get("case_alert");
        String id = ca instanceof Map<?, ?> m ? str(((Map<String, Object>) m).get("alert_id")) : null;
        if (id == null || id.isBlank()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "invalid_request", "case_alert.alert_id is required", "case_alert.alert_id");
        }
        MockAlert a = world.byExternalId(id).orElse(null);
        String alertToken = a == null ? null : world.caseAlertView(a).get("token") instanceof String t ? t : null;
        if (alertToken == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "not_found", "No case alert for alert id " + id);
        }
        return ResponseEntity.status(HttpStatus.SEE_OTHER)
                .location(URI.create(MockProperties.BASE_PATH + "/alerts/" + alertToken + "?apiVersion=" + props.apiVersion()))
                .build();
    }

    @GetMapping("/alerts/{token}")
    public Map<String, Object> getCaseAlert(@PathVariable String token) {
        MockAlert a = world.byAlertToken(token)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "not_found", "Alert not found"));
        return Map.of("case_alert", world.caseAlertView(a));
    }

    // ---------------------------------------------------------- cases / reviews

    @GetMapping("/cases")
    public Map<String, Object> listCases(@RequestParam(required = false) String workflow,
                                         @RequestParam(required = false) Integer first,
                                         @RequestParam(required = false) String after,
                                         @RequestParam(required = false) Integer last,
                                         @RequestParam(required = false) String before,
                                         @RequestParam(required = false) String createdBetween,
                                         @RequestParam(required = false) String updatedBetween) {
        return world.listCases(workflow, first, after, last, before, createdBetween, updatedBetween);
    }

    @GetMapping("/cases/{token}")
    public Map<String, Object> getCase(@PathVariable String token) {
        return world.getCase(token);
    }

    @GetMapping("/reviews")
    public Map<String, Object> listReviews(@RequestParam(name = "case_token", required = false) String caseToken,
                                           @RequestParam(name = "assignee_email", required = false) String assigneeEmail,
                                           @RequestParam(name = "queue_canonical_id", required = false) String queue,
                                           @RequestParam(name = "type_canonical_id", required = false) String type,
                                           @RequestParam(name = "internal_control_number", required = false) String icn,
                                           @RequestParam(name = "created_at", required = false) String createdAt,
                                           @RequestParam(name = "updated_at", required = false) String updatedAt,
                                           @RequestParam(name = "completed_at", required = false) String completedAt,
                                           @RequestParam(name = "cancelled_at", required = false) String cancelledAt,
                                           @RequestParam(name = "page_size", required = false) Integer pageSize,
                                           @RequestParam(name = "page_token", required = false) String pageToken) {
        return world.listReviews(new MockWorld.ReviewQuery(caseToken, assigneeEmail, queue, type, icn,
                createdAt, updatedAt, completedAt, cancelledAt), pageSize, pageToken);
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static boolean eq(String expected, String actual) {
        return expected == null || expected.isBlank() ? actual == null || actual.isBlank() : expected.equals(actual);
    }
}
