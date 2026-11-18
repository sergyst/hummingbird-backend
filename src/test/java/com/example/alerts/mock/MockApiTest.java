package com.example.alerts.mock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.alerts.AlertsApplication;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;

class MockApiTest {

    static ConfigurableApplicationContext ctx;
    static String base;
    static final HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build();

    @BeforeAll
    static void start() {
        ctx = SpringApplication.run(AlertsApplication.class, "--server.port=0", "--hummingbird.mock.enabled=true",
                "--hummingbird.mock.webhooks-enabled=false", "--hummingbird.mock.process-delay=PT0S",
                "--spring.datasource.url=jdbc:h2:mem:mockapitest", "--spring.main.banner-mode=off");
        base = "http://localhost:" + ctx.getEnvironment().getProperty("local.server.port") + MockProperties.BASE_PATH;
    }

    @AfterAll
    static void stop() {
        ctx.close();
    }

    static HttpResponse<String> call(String method, String path, String token, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path)).header("Content-Type", "application/json")
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        if (token != null) b.header("Authorization", "Bearer " + token);
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    static String token() throws Exception {
        String res = call("POST", "/oauth/token", null,
                "{\"grant_type\":\"client_credentials\",\"client_id\":\"mock-client\",\"client_secret\":\"mock-secret\"}").body();
        return res.replaceAll(".*\"access_token\":\"([^\"]+)\".*", "$1");
    }

    @Test
    void rejectsBadCredentials() throws Exception {
        var res = call("POST", "/oauth/token", null,
                "{\"grant_type\":\"client_credentials\",\"client_id\":\"x\",\"client_secret\":\"y\"}");
        assertEquals(401, res.statusCode());
        assertTrue(res.body().contains("invalid_client"));
    }

    @Test
    void requiresTokenAndApiVersion() throws Exception {
        assertEquals(401, call("GET", "/alert_imports/x?apiVersion=2026-02-05", null, null).statusCode());
        assertEquals(400, call("GET", "/alert_imports/x", token(), null).statusCode());
    }

    @Test
    void alertLifecycleAndLookupRedirect() throws Exception {
        String t = token();
        String body = """
                {"alerts":[{"id":"T-1","triggered_at":"2026-11-17T10:00:00Z","rule":"Structuring test"}]}""";
        var created = call("POST", "/alert_imports?apiVersion=2026-02-05", t, body);
        assertEquals(200, created.statusCode());
        String processing = created.body().replaceAll(".*\"token\":\"([^\"]+)\".*", "$1");

        assertTrue(call("GET", "/alert_imports/" + processing + "?apiVersion=2026-02-05", t, null).body().contains("\"pending\""));
        assertEquals(404, call("POST", "/alerts/id_lookup?apiVersion=2026-02-05", t, "{\"case_alert\":{\"alert_id\":\"T-1\"}}").statusCode());

        assertEquals(200, call("POST", "/mock/alerts/T-1/advance", null, null).statusCode());
        assertTrue(call("GET", "/alert_imports/" + processing + "?apiVersion=2026-02-05", t, null).body().contains("\"processed\""));

        // id_lookup answers 303 -> GET /alerts/{token}; the client follows it
        var lookup = call("POST", "/alerts/id_lookup?apiVersion=2026-02-05", t, "{\"case_alert\":{\"alert_id\":\"T-1\"}}");
        assertEquals(200, lookup.statusCode());
        assertTrue(lookup.body().contains("\"outcome\":\"opened\""));
    }

    @Test
    void validatesPayload() throws Exception {
        var res = call("POST", "/alert_imports?apiVersion=2026-02-05", token(), "{\"alerts\":[{\"id\":\"V-1\"}]}");
        assertEquals(422, res.statusCode());
        assertTrue(res.body().contains("alerts[0].rule"));
    }

    private static String alert(String id, String rule, String comments, String extra) {
        return "{\"alerts\":[{\"id\":\"" + id + "\",\"triggered_at\":\"2026-11-17T10:00:00Z\",\"rule\":\"" + rule
                + "\",\"comments\":\"" + comments + "\"}]" + extra + "}";
    }

    private static String processingToken(String body) {
        return body.replaceAll(".*\"token\":\"([^\"]+)\".*", "$1");
    }

    @Test
    void commentFailMakesTheAlertFailAndItCanBeCorrected() throws Exception {
        String t = token();
        var created = call("POST", "/alert_imports?apiVersion=2026-02-05", t, alert("F-1", "Whitelist: address added", "fail", ""));
        assertEquals(200, created.statusCode());
        String processing = processingToken(created.body());

        call("POST", "/mock/alerts/F-1/fast-forward", null, null);
        String view = call("GET", "/alert_imports/" + processing + "?apiVersion=2026-02-05", t, null).body();
        assertTrue(view.contains("\"state\":\"error\""), view);
        assertTrue(view.contains("simulated by 'fail'"), view);
        assertEquals(404, call("POST", "/alerts/id_lookup?apiVersion=2026-02-05", t, "{\"case_alert\":{\"alert_id\":\"F-1\"}}").statusCode());

        // the corrected alert (same id, no "fail") is accepted again and processes normally
        var resent = call("POST", "/alert_imports?apiVersion=2026-02-05", t, alert("F-1", "Whitelist: address added", "all good", ""));
        String again = processingToken(resent.body());
        call("POST", "/mock/alerts/F-1/fast-forward", null, null);
        assertTrue(call("GET", "/alert_imports/" + again + "?apiVersion=2026-02-05", t, null).body().contains("\"processed\""));
    }

    @Test
    void httpDirectiveRejectsTheRequestAndCreatesNothing() throws Exception {
        String t = token();
        var res = call("POST", "/alert_imports?apiVersion=2026-02-05", t, alert("H-1", "r", "mock:http=503", ""));
        assertEquals(503, res.statusCode());
        assertTrue(res.body().contains("\"success\":false"), res.body());
        assertTrue(res.body().contains("\"type\":\"GENERIC\""), res.body());
        assertEquals(404, call("POST", "/mock/alerts/H-1/advance", null, null).statusCode(), "alert must not exist");
        assertEquals(429, call("POST", "/alert_imports?apiVersion=2026-02-05", t, alert("H-2", "r", "mock:http=429", "")).statusCode());
    }

    @Test
    void unknownDirectiveGets422() throws Exception {
        var res = call("POST", "/alert_imports?apiVersion=2026-02-05", token(), alert("U-1", "r", "mock:bogus", ""));
        assertEquals(422, res.statusCode());
        assertTrue(res.body().contains("mock:bogus"));
        assertTrue(call("GET", "/mock/directives", null, null).body().contains("mock:delay"));
    }

    @Test
    void casesAndReviewsFollowTheDocumentedShapes() throws Exception {
        call("POST", "/mock/reset", null, null); // tests share one in-memory world; start from an empty one
        String t = token();
        call("POST", "/alert_imports?apiVersion=2026-02-05", t,
                alert("C-1", "Whitelist: address added", "mock:filed", ",\"workflow\":\"triage\""));
        call("POST", "/mock/alerts/C-1/fast-forward", null, null);

        String list = call("GET", "/cases?first=25&workflow=triage&apiVersion=2026-02-05", t, null).body();
        assertTrue(list.contains("\"cases\":[{") && list.contains("\"case\":{\"token\":\"cas_"), list);
        assertTrue(list.contains("\"has_next_page\":false"), list);
        assertTrue(call("GET", "/cases?workflow=nope&apiVersion=2026-02-05", t, null).body().contains("\"cases\":[]"));

        String caseToken = list.replaceAll(".*?\"case\":\\{\"token\":\"(cas_[a-z0-9]+)\".*", "$1");
        String one = call("GET", "/cases/" + caseToken + "?apiVersion=2026-02-05", t, null).body();
        assertTrue(one.contains("\"workflow\":\"triage\"") && one.contains("\"rule\":\"Whitelist: address added\""), one);
        assertTrue(one.contains("\"choice_id\":\"file_sar\"") && one.contains("\"jurisdiction\":\"FINCEN\""), one);

        String reviews = call("GET", "/reviews?case_token=" + caseToken + "&page_size=25&apiVersion=2026-02-05", t, null).body();
        assertTrue(reviews.contains("\"status\":\"COMPLETED\"") && reviews.contains("\"next_page_token\":null"), reviews);

        var missing = call("GET", "/cases/cas_nope?apiVersion=2026-02-05", t, null);
        assertEquals(404, missing.statusCode());
        assertTrue(missing.body().contains("\"type\":\"NOT_FOUND\""));
        assertEquals(401, call("GET", "/cases?apiVersion=2026-02-05", null, null).statusCode());
    }

    @Test
    void filingRejectedEndsWithRejectedFilingStatus() throws Exception {
        String t = token();
        call("POST", "/alert_imports?apiVersion=2026-02-05", t, alert("R-1", "r", "mock:filing_rejected", ""));
        call("POST", "/mock/alerts/R-1/fast-forward", null, null);
        var lookup = call("POST", "/alerts/id_lookup?apiVersion=2026-02-05", t, "{\"case_alert\":{\"alert_id\":\"R-1\"}}");
        assertTrue(lookup.body().contains("\"outcome\":\"filed\""), lookup.body());
        // the filing status is not part of the documented API; the mock's own debug view shows it
        String debug = call("GET", "/mock/alerts", null, null).body();
        assertTrue(debug.contains("\"filing_status\":\"rejected\""), debug);
    }

    @Test
    void caseAlertHasTheDocumentedShapeAndLifecycleEvents() throws Exception {
        String t = token();
        call("POST", "/alert_imports?apiVersion=2026-02-05", t, alert("D-1", "Structuring", "mock:filed", ""));
        call("POST", "/mock/alerts/D-1/fast-forward", null, null);

        String body = call("POST", "/alerts/id_lookup?apiVersion=2026-02-05", t, "{\"case_alert\":{\"alert_id\":\"D-1\"}}").body();

        assertTrue(body.contains("\"id\":\"D-1\"") && body.contains("\"rule\":\"Structuring\""), body);
        assertTrue(body.contains("\"outcome\":\"filed\""), body);
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"event\":\"([a-z_]+)\"").matcher(body);
        java.util.List<String> events = new java.util.ArrayList<>();
        while (m.find()) events.add(m.group(1));
        assertEquals(java.util.List.of("processed", "opened", "escalated", "filed"), events, body);
        assertTrue(body.contains("\"timestamp\":") && body.contains("\"review_type_name\":") && body.contains("\"full_name\":\"Mock Analyst\""), body);
        assertTrue(!body.contains("occurred_at") && !body.contains("\"alert_id\""), "old non-documented fields are gone: " + body);
    }

    // ---------------------------------------------------- cases / reviews parameters

    private static int count(String body, String needle) {
        return body.split(java.util.regex.Pattern.quote(needle), -1).length - 1;
    }

    private static java.util.List<String> cursors(String body) {
        var m = java.util.regex.Pattern.compile("\"cursor\":\"(cas_[a-z0-9]+)\"").matcher(body);
        var out = new java.util.ArrayList<String>();
        while (m.find()) out.add(m.group(1));
        return out;
    }

    /** Three processed alerts: P-1 and P-2 in the default (triage) workflow, P-3 in aml-investigation. */
    private static String seedThree() throws Exception {
        call("POST", "/mock/reset", null, null);
        String t = token();
        call("POST", "/alert_imports?apiVersion=2026-02-05", t, alert("P-1", "r", "one", ""));
        call("POST", "/alert_imports?apiVersion=2026-02-05", t, alert("P-2", "r", "two", ""));
        call("POST", "/alert_imports?apiVersion=2026-02-05", t, alert("P-3", "r", "three", ",\"workflow\":\"aml-investigation\""));
        for (String id : new String[] {"P-1", "P-2", "P-3"}) call("POST", "/mock/alerts/" + id + "/fast-forward", null, null);
        return t;
    }

    private static String get(String t, String path) throws Exception {
        return call("GET", path + (path.contains("?") ? "&" : "?") + "apiVersion=2026-02-05", t, null).body();
    }

    @Test
    void casesPageBackwardsAndForwards() throws Exception {
        String t = seedThree();
        var all = cursors(get(t, "/cases"));
        assertEquals(3, all.size());

        var forward = get(t, "/cases?first=1&after=" + all.get(0));
        assertEquals(java.util.List.of(all.get(1)), cursors(forward));
        assertTrue(forward.contains("\"has_next_page\":true") && forward.contains("\"has_previous_page\":true"), forward);

        assertEquals(java.util.List.of(all.get(2)), cursors(get(t, "/cases?last=1")), "last n of the whole list");
        var back = get(t, "/cases?last=1&before=" + all.get(2));
        assertEquals(java.util.List.of(all.get(1)), cursors(back));
        assertEquals(java.util.List.of(all.get(0)), cursors(get(t, "/cases?before=" + all.get(1))));
        assertTrue(get(t, "/cases?last=2").contains("\"has_previous_page\":true"));

        assertEquals(400, call("GET", "/cases?after=cas_nope&apiVersion=2026-02-05", t, null).statusCode());
        assertEquals(400, call("GET", "/cases?before=cas_nope&apiVersion=2026-02-05", t, null).statusCode());
    }

    @Test
    void casesFilterByWorkflowAndDateRanges() throws Exception {
        String t = seedThree();
        assertEquals(1, cursors(get(t, "/cases?workflow=aml-investigation")).size());
        assertEquals(2, cursors(get(t, "/cases?workflow=triage")).size());

        String today = java.time.LocalDate.now(java.time.ZoneOffset.UTC).toString();
        assertEquals(3, cursors(get(t, "/cases?createdBetween=2020-01-01...")).size());
        assertEquals(3, cursors(get(t, "/cases?createdBetween=" + today)).size());
        assertEquals(0, cursors(get(t, "/cases?createdBetween=...2020-01-01")).size());
        assertEquals(3, cursors(get(t, "/cases?updatedBetween=2020-01-01...2100-01-01")).size());
        assertEquals(0, cursors(get(t, "/cases?updatedBetween=...2020-01-01")).size());
        assertEquals(1, cursors(get(t, "/cases?updatedBetween=2020-01-01...&workflow=aml-investigation")).size());

        var bad = call("GET", "/cases?createdBetween=yesterday&apiVersion=2026-02-05", t, null);
        assertEquals(400, bad.statusCode());
        assertTrue(bad.body().contains("createdBetween"));
        assertEquals(400, call("GET", "/cases?createdBetween=&apiVersion=2026-02-05", t, null).statusCode());
    }

    @Test
    void reviewFilters() throws Exception {
        String t = seedThree();
        String r = "\"case_token\"";
        assertEquals(3, count(get(t, "/reviews"), r));
        assertEquals(1, count(get(t, "/reviews?type_canonical_id=aml-investigation"), r));
        assertEquals(2, count(get(t, "/reviews?queue_canonical_id=triage"), r));
        assertEquals(3, count(get(t, "/reviews?assignee_email=analyst@mock.local"), r));
        assertEquals(0, count(get(t, "/reviews?assignee_email=someone@else.com"), r));
        assertEquals(1, count(get(t, "/reviews?internal_control_number=ICN-P-2"), r));

        String today = java.time.LocalDate.now(java.time.ZoneOffset.UTC).toString();
        assertEquals(3, count(get(t, "/reviews?created_at=" + today), r));
        assertEquals(0, count(get(t, "/reviews?created_at=...2020-01-01"), r));
        assertEquals(3, count(get(t, "/reviews?updated_at=2020-01-01..."), r));

        // every default alert is "not_filed" => its review is completed; nothing is ever cancelled
        assertEquals(3, count(get(t, "/reviews?completed_at=2020-01-01..."), r));
        assertEquals(0, count(get(t, "/reviews?completed_at="), r), "empty = only reviews that are not completed");
        assertEquals(3, count(get(t, "/reviews?cancelled_at="), r), "empty = never cancelled");
        assertEquals(0, count(get(t, "/reviews?cancelled_at=2020-01-01..."), r));

        // filters combine
        assertEquals(1, count(get(t, "/reviews?queue_canonical_id=triage&internal_control_number=ICN-P-1"), r));
        assertEquals(0, count(get(t, "/reviews?queue_canonical_id=aml-investigation&internal_control_number=ICN-P-1"), r));
        assertEquals(400, call("GET", "/reviews?created_at=nope&apiVersion=2026-02-05", t, null).statusCode());
        assertEquals(400, call("GET", "/reviews?created_at=&apiVersion=2026-02-05", t, null).statusCode());
    }

    @Test
    void reviewPagingAndPageSizeLimits() throws Exception {
        String t = seedThree();
        String r = "\"case_token\"";
        String first = get(t, "/reviews?page_size=2");
        assertEquals(2, count(first, r));
        assertTrue(first.contains("\"next_page_token\":\"2\""), first);
        String second = get(t, "/reviews?page_size=2&page_token=2");
        assertEquals(1, count(second, r));
        assertTrue(second.contains("\"next_page_token\":null"), second);

        var tooBig = call("GET", "/reviews?page_size=26&apiVersion=2026-02-05", t, null);
        assertEquals(400, tooBig.statusCode());
        assertTrue(tooBig.body().contains("page_size"));
        assertEquals(400, call("GET", "/reviews?page_size=0&apiVersion=2026-02-05", t, null).statusCode());
        assertEquals(400, call("GET", "/reviews?page_token=abc&apiVersion=2026-02-05", t, null).statusCode());
    }

    @Test
    void notYetCompletedReviewsMatchTheEmptyCompletedFilter() throws Exception {
        call("POST", "/mock/reset", null, null);
        String t = token();
        call("POST", "/alert_imports?apiVersion=2026-02-05", t, alert("O-1", "r", "mock:escalated", ""));
        call("POST", "/mock/alerts/O-1/fast-forward", null, null);
        assertEquals(1, count(get(t, "/reviews?completed_at="), "\"case_token\""));
        assertEquals(0, count(get(t, "/reviews?completed_at=2020-01-01..."), "\"case_token\""));
    }
}

