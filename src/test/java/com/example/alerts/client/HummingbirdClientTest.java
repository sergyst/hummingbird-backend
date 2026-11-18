package com.example.alerts.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.alerts.client.model.AlertImportRequest;
import com.example.alerts.client.model.AlertImportStatus;
import com.example.alerts.client.model.AlertImportsCreated;
import com.example.alerts.client.model.CaseAlert;
import com.example.alerts.client.model.CaseDetailResponse;
import com.example.alerts.client.model.CaseList;
import com.example.alerts.client.model.ReviewList;
import com.example.alerts.client.model.ReviewStatus;
import com.example.alerts.config.HummingbirdProperties;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Runs the real client (RestClient + Jackson) against a local HTTP server that answers with response bodies
 * shaped like the Hummingbird API reference, including fields the client does not model.
 */
class HummingbirdClientTest {

    private HttpServer server;
    private HummingbirdClient client;
    /** "METHOD /path?query" of every request after the token call, and the Authorization header it carried. */
    private final List<String> requests = new CopyOnWriteArrayList<>();
    private final List<String> authHeaders = new CopyOnWriteArrayList<>();
    private final AtomicInteger tokenCalls = new AtomicInteger();
    private final AtomicInteger rateLimited = new AtomicInteger();

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
        client = new HummingbirdClient(new HummingbirdProperties(
                "http://127.0.0.1:" + server.getAddress().getPort(), "2026-02-05", "cid", "sec", "", false,
                Duration.ofSeconds(30), 4));
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private void handle(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        String query = ex.getRequestURI().getRawQuery();
        String call = ex.getRequestMethod() + " " + path + (query == null ? "" : "?" + query);
        if (path.equals("/oauth/token")) {
            tokenCalls.incrementAndGet();
            reply(ex, 200, "{\"access_token\":\"tok-" + tokenCalls.get() + "\",\"token_type\":\"Bearer\",\"expires_in\":3600,\"scope\":\"x\"}");
            return;
        }
        requests.add(call);
        authHeaders.add(String.valueOf(ex.getRequestHeaders().getFirst("Authorization")));
        switch (call.replaceAll("\\?.*", "")) {
            case "POST /alert_imports" -> reply(ex, 201, "{\"success\":true,\"alerts\":[{\"token\":\"aim_abc\",\"id\":\"WL-1\"}]}");
            case "GET /alert_imports/aim_abc" -> reply(ex, 200, """
                    {"success":true,"alert":{"token":"aim_abc","id":"WL-1","state":"processed",
                     "case_token":"cas_1","review_token":"rev_1","processed_at":"2026-11-01T10:15:30Z","error":null,
                     "something_new":{"nested":true}}}""");
            case "POST /alerts/id_lookup" -> {
                ex.getResponseHeaders().add("Location", "/alerts/aln_9?apiVersion=2026-02-05");
                reply(ex, 303, "");
            }
            case "GET /alerts/aln_9" -> reply(ex, 200, """
                    {"case_alert":{"outcome":"filed","group_id":7,"id":"WL-1","token":"aln_9","review_tags":["a","b"],
                     "rule":"Structuring","description":null,"comments":"c","other_info":[{"label":"L","value":"V"}],
                     "lifecycle_events":[{"timestamp":"2026-11-01T10:20:00Z","event":"OPENED","case_token":"cas_1",
                     "review_token":"rev_1","review_type_name":"Triage","actor":{"account_token":"acc_1","full_name":"Ann Analyst"}}]}}""");
            case "GET /cases" -> reply(ex, 200, """
                    {"success":true,"cases":[{"cursor":"c1","case":{"token":"cas_1","created_at":"2026-11-01T10:00:00Z",
                     "updated_at":"2026-11-02T11:00:00Z"}}],
                     "page_info":{"end_cursor":"c1","has_next_page":true,"has_previous_page":false,"start_cursor":"c1"}}""");
            case "GET /cases/cas_1" -> reply(ex, 200, """
                    {"success":true,"case":{"token":"cas_1","id":"CASE-1","name":"Case for WL-1",
                     "created_at":"2026-11-01T10:00:00Z","updated_at":"2026-11-02T11:00:00Z",
                     "reviews":[{"token":"rev_1","workflow":"triage","stage":"Complete","status":"completed",
                                 "alerts":[{"token":"aln_9"}],"decisions":[]}],
                     "filings":[{"jurisdiction":"US","workflow":"filing","token":"fil_1"}]}}""");
            case "GET /reviews" -> reply(ex, 200, """
                    {"reviews":[{"token":"rev_1","case_token":"cas_1","status":"READY_FOR_REVIEW",
                      "stage":{"title":"Escalation","token":"stg_1"},"queue":{"canonical_id":"q","name":"AML","token":"q_1"},
                      "assignee":{"token":"u_1","email":"a@b.c","name":"Ann"},"cancellation":null,
                      "type_canonical_id":"aml-investigation","internal_control_number":"ICN-1","reference_id":null,
                      "review_due_at":null,"created_at":"2026-11-01T10:00:00Z","updated_at":"2026-11-01T12:00:00Z",
                      "completed_at":null,"future_field":1}],"next_page_token":"pg2"}""");
            case "GET /cases/bad" -> reply(ex, 422,
                    "{\"success\":false,\"error\":{\"type\":\"invalid_request\",\"message\":\"Bad token\",\"param\":\"case_token\"},\"trace_id\":\"tr-1\"}");
            case "GET /cases/missing" -> reply(ex, 404, "<html>nope</html>");
            case "GET /cases/stale" -> {
                // only the second token is accepted: the first one has been "revoked"
                if ("Bearer tok-1".equals(ex.getRequestHeaders().getFirst("Authorization"))) {
                    reply(ex, 401, "{\"success\":false,\"error\":{\"type\":\"authentication_error\",\"message\":\"Invalid or expired access token\"}}");
                } else {
                    reply(ex, 200, "{\"success\":true,\"case\":{\"token\":\"stale\",\"reviews\":[]}}");
                }
            }
            case "GET /cases/revoked" -> reply(ex, 401,
                    "{\"success\":false,\"error\":{\"type\":\"authentication_error\",\"message\":\"Invalid or expired access token\"}}");
            case "GET /cases/busy" -> {
                if (rateLimited.getAndIncrement() == 0) {
                    ex.getResponseHeaders().add("Retry-After", "1");
                    reply(ex, 429, "{\"success\":false,\"error\":{\"type\":\"rate_limited\",\"message\":\"slow down\"}}");
                } else {
                    reply(ex, 200, "{\"success\":true,\"case\":{\"token\":\"busy\",\"reviews\":[]}}");
                }
            }
            default -> reply(ex, 404, "{}");
        }
    }

    private static void reply(HttpExchange ex, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) {
            ex.getResponseBody().write(bytes);
        }
        ex.close();
    }

    @Test
    void alertImportResponseGivesTheProcessingToken() {
        AlertImportsCreated res = client.upsertAlertImport(new AlertImportRequest(List.of(), null, null));

        assertEquals("aim_abc", res.processingTokenFor("WL-1"));
        assertNull(res.processingTokenFor("someone-else"));
    }

    @Test
    void importStatusMapsSnakeCaseFieldsAndIgnoresUnknownOnes() {
        AlertImportStatus a = client.getAlertImport("aim_abc").alert();

        assertEquals("processed", a.state());
        assertEquals("cas_1", a.caseToken());
        assertEquals("rev_1", a.reviewToken());
        assertEquals(Instant.parse("2026-11-01T10:15:30Z"), a.processedAt());
        assertNull(a.error());
    }

    @Test
    void caseAlertLookupFollowsTheRedirectAndParsesLifecycleEvents() {
        CaseAlert ca = client.lookupCaseAlert("WL-1").caseAlert();

        assertEquals("filed", ca.outcome());
        assertEquals("aln_9", ca.token());
        assertEquals(7L, ca.groupId());
        assertEquals(List.of("a", "b"), ca.reviewTags());
        assertEquals("Ann Analyst", ca.lifecycleEvents().get(0).actor().fullName());
        assertEquals("Triage", ca.lifecycleEvents().get(0).reviewTypeName());
        assertEquals(Instant.parse("2026-11-01T10:20:00Z"), ca.lifecycleEvents().get(0).timestamp());
        assertTrue(requests.contains("GET /alerts/aln_9?apiVersion=2026-02-05"), requests.toString());
    }

    @Test
    void caseListHasCursorsAndPageInfo() {
        CaseList list = client.listCases(Map.of("first", "25", "workflow", "triage", "after", ""));

        assertEquals("cas_1", list.cases().get(0).kase().token());
        assertEquals(Instant.parse("2026-11-02T11:00:00Z"), list.cases().get(0).kase().updatedAt());
        assertTrue(list.pageInfo().hasNext());
        assertEquals("c1", list.pageInfo().endCursor());
        String call = requests.get(0);
        assertTrue(call.contains("first=25") && call.contains("workflow=triage"), call);
        assertTrue(!call.contains("after="), "blank params are dropped: " + call);
    }

    @Test
    void caseDetailHasReviewsWithStageAsAString() {
        CaseDetailResponse.Case c = client.getCase("cas_1").kase();

        assertEquals("Case for WL-1", c.name());
        assertEquals("CASE-1", c.id());
        assertEquals(1, c.reviews().size());
        CaseDetailResponse.Review r = c.reviews().get(0);
        assertEquals("triage", r.workflow());
        assertEquals("Complete", r.stage());
        assertEquals(ReviewStatus.COMPLETED, r.reviewStatus());
    }

    @Test
    void reviewListHasObjectsForStageQueueAndAssignee() {
        ReviewList list = client.listReviews(Map.of("case_token", "cas_1", "page_size", "25"));

        ReviewList.Review r = list.reviews().get(0);
        assertEquals("cas_1", r.caseToken());
        assertEquals(ReviewStatus.READY_FOR_REVIEW, r.reviewStatus());
        assertEquals("Escalation", r.stage().title());
        assertEquals("AML", r.queue().name());
        assertEquals("a@b.c", r.assignee().email());
        assertEquals("aml-investigation", r.typeCanonicalId());
        assertEquals("ICN-1", r.internalControlNumber());
        assertNull(r.completedAt());
        assertEquals("pg2", list.nextPageToken());
    }

    @Test
    void everyCallCarriesTheBearerTokenAndApiVersion_andTheTokenIsFetchedOnce() {
        client.getCase("cas_1");
        client.listCases(Map.of());
        client.listReviews(Map.of());

        assertEquals(1, tokenCalls.get(), "token is cached");
        assertEquals(List.of("Bearer tok-1", "Bearer tok-1", "Bearer tok-1"), authHeaders);
        assertTrue(requests.stream().allMatch(r -> r.contains("apiVersion=2026-02-05")), requests.toString());
    }

    @Test
    void rawJsonMethodsKeepFieldsTheTypedModelDoesNotKnow() {
        Map<String, Object> raw = client.getCaseJson("cas_1");

        @SuppressWarnings("unchecked")
        Map<String, Object> kase = (Map<String, Object>) raw.get("case");
        assertTrue(kase.containsKey("filings"), "filings are not modelled but must survive the raw path");
        assertEquals("CASE-1", kase.get("id"));
    }

    @Test
    void documentedErrorBodyBecomesAnExceptionWithTypeMessageParamAndTraceId() {
        HummingbirdApiException e = assertThrows(HummingbirdApiException.class, () -> client.getCase("bad"));

        assertEquals(422, e.getStatus());
        assertEquals("invalid_request", e.getCode());
        assertEquals("Bad token (case_token)", e.getMessage());
        assertEquals("tr-1", e.getTraceId());
    }

    @Test
    void aNonJsonErrorBodyFallsBackToTheHttpStatus() {
        HummingbirdApiException e = assertThrows(HummingbirdApiException.class, () -> client.getCase("missing"));

        assertEquals(404, e.getStatus());
        assertEquals("HTTP_404", e.getCode());
    }

    @Test
    void rateLimitedCallsAreRetriedAfterRetryAfter() {
        long start = System.nanoTime();
        CaseDetailResponse res = client.getCase("busy");

        assertEquals("busy", res.kase().token());
        assertEquals(2, rateLimited.get());
        assertTrue(Duration.ofNanos(System.nanoTime() - start).toMillis() >= 900, "waited for Retry-After");
    }

    @Test
    void aRejectedTokenIsReplacedAndTheCallRetriedOnce() {
        client.getCase("cas_1"); // fetches tok-1 and caches it

        CaseDetailResponse res = client.getCase("stale"); // tok-1 is now rejected with 401

        assertEquals("stale", res.kase().token());
        assertEquals(2, tokenCalls.get(), "a second token was requested");
        assertEquals("Bearer tok-2", authHeaders.get(authHeaders.size() - 1));
    }

    @Test
    void aSecond401IsNotRetriedForever() {
        HummingbirdApiException e = assertThrows(HummingbirdApiException.class, () -> client.getCase("revoked"));

        assertEquals(401, e.getStatus());
        assertEquals("authentication_error", e.getCode());
        assertEquals(2, tokenCalls.get(), "one renewal, then give up");
        assertEquals(2, requests.stream().filter(r -> r.contains("/cases/revoked")).count());
    }

    @Test
    void missingCredentialsFailBeforeAnyRequest() {
        HummingbirdClient noCreds = new HummingbirdClient(new HummingbirdProperties(
                "http://127.0.0.1:" + server.getAddress().getPort(), "2026-02-05", "", "", "", false,
                Duration.ofSeconds(30), 4));

        HummingbirdApiException e = assertThrows(HummingbirdApiException.class, () -> noCreds.getCase("cas_1"));

        assertEquals(503, e.getStatus());
        assertEquals("NOT_CONFIGURED", e.getCode());
        assertTrue(requests.isEmpty());
    }
}
