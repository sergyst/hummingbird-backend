package com.example.alerts.mock;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import com.example.alerts.mock.MockAlert.Plan;
import com.example.alerts.mock.MockAlert.Step;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** In-memory "Hummingbird": all alerts plus the engine that moves them through their lifecycle. */
@ConditionalOnProperty(prefix = "hummingbird.mock", name = "enabled", havingValue = "true")
@Component
public class MockWorld {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789";

    private final MockProperties props;
    private final WebhookSender webhooks;
    private final Map<String, MockAlert> byExternalId = new LinkedHashMap<>();
    private final Map<String, MockAlert> byProcessingToken = new HashMap<>();
    private final Map<String, MockAlert> byAlertToken = new HashMap<>();

    public MockWorld(MockProperties props, WebhookSender webhooks) {
        this.props = props;
        this.webhooks = webhooks;
    }

    static String token(String prefix) {
        StringBuilder sb = new StringBuilder(prefix).append('_');
        for (int i = 0; i < 20; i++) sb.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        return sb.toString();
    }

    // ------------------------------------------------------------------ API

    /** POST /alert_imports. Returns the alert (new or existing, for idempotent re-sends). */
    @SuppressWarnings("unchecked")
    public synchronized List<MockAlert> importAlerts(Map<String, Object> body) {
        if (!(body.get("alerts") instanceof List<?> list) || list.isEmpty()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "invalid_request", "alerts must be a non-empty array", "alerts");
        }
        Map<String, Object> data = body.get("data") instanceof Map<?, ?> d ? (Map<String, Object>) d : Map.of();
        List<MockAlert> result = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) {
            if (!(list.get(i) instanceof Map<?, ?> raw)) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "invalid_request", "alert must be an object", "alerts[" + i + "]");
            }
            Map<String, Object> a = (Map<String, Object>) raw;
            String at = "alerts[" + i + "].";
            String id = requireString(a, "id", at + "id");
            requireString(a, "rule", at + "rule");
            String triggered = requireString(a, "triggered_at", at + "triggered_at");
            try {
                Instant.parse(triggered);
            } catch (RuntimeException e) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "invalid_request",
                        "triggered_at must be an ISO-8601 UTC timestamp", at + "triggered_at");
            }
            checkReferences(a, data, at);
            Directives directives = Directives.parse(a, at + "comments");
            if (directives.httpStatus() != null) {
                HttpStatus status = HttpStatus.valueOf(directives.httpStatus());
                throw new ApiException(status, "mock_rejected",
                        "Simulated " + status.value() + " (" + status.getReasonPhrase() + ") by mock directive", at + "comments");
            }

            MockAlert existing = byExternalId.get(id);
            if (existing != null) {
                if ("error".equals(existing.state)) { // an errored alert may be corrected and re-sent
                    unindex(existing);
                } else { // processed / pending / dismissed / stuck: duplicate is a no-op
                    result.add(existing);
                    continue;
                }
            }
            MockAlert alert = new MockAlert(id, token("aim"), directives, a, body);
            schedule(alert);
            byExternalId.put(id, alert);
            byProcessingToken.put(alert.processingToken, alert);
            result.add(alert);
        }
        return result;
    }

    public synchronized Optional<MockAlert> byProcessingToken(String token) {
        return Optional.ofNullable(byProcessingToken.get(token));
    }

    public synchronized Optional<MockAlert> byExternalId(String id) {
        return Optional.ofNullable(byExternalId.get(id));
    }

    public synchronized Optional<MockAlert> byAlertToken(String token) {
        return Optional.ofNullable(byAlertToken.get(token));
    }

    // ------------------------------------------------------------- snapshots

    /** Snapshot taken under the lock, safe to serialise. */
    public synchronized Map<String, Object> importView(MockAlert a) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("token", a.processingToken);
        m.put("id", a.externalId);
        m.put("state", a.state);
        m.put("case_token", a.caseToken);
        m.put("review_token", a.reviewToken);
        m.put("processed_at", a.processedAt == null ? null : a.processedAt.toString());
        m.put("error", a.error);
        return m;
    }

    /** The case alert as GET /alerts/{token} documents it (additionalProperties are not allowed there). */
    public synchronized Map<String, Object> caseAlertView(MockAlert a) {
        Map<String, Object> d = a.alertData;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("outcome", a.outcome);
        m.put("group_id", null);
        m.put("id", a.externalId);
        m.put("token", a.alertToken);
        m.put("review_tags", d.get("review_tags") instanceof List<?> l ? List.copyOf(l) : List.of());
        m.put("rule", d.get("rule"));
        m.put("description", d.get("description"));
        m.put("comments", d.get("comments"));
        m.put("other_info", d.get("other_info") instanceof List<?> l ? List.copyOf(l) : List.of());
        m.put("lifecycle_events", lifecycleView(a));
        return m;
    }

    /**
     * Translates the mock's internal event log into the documented lifecycle events: {@code event} is one of
     * processed, opened, escalated, filed, not_filed, dismissed, each with timestamp, case/review token and the
     * review type; steps taken by an analyst carry an {@code actor}.
     */
    private List<Map<String, Object>> lifecycleView(MockAlert a) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> e : a.lifecycleEvents) {
            List<String> events = switch (String.valueOf(e.get("type"))) {
                case "alert.processed" -> List.of("processed", "opened");
                case "review.completed" -> a.plan == MockAlert.Plan.NOT_FILED ? List.of() : List.of("escalated");
                case "action.decision_made" -> "no_filing".equals(e.get("choice")) ? List.of("not_filed") : List.of();
                case "filing.status_updated" -> "submitted".equals(e.get("status")) ? List.of("filed") : List.of();
                default -> List.of();
            };
            for (String event : events) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("timestamp", e.get("occurred_at"));
                m.put("event", event);
                m.put("case_token", a.caseToken);
                m.put("review_token", a.reviewToken);
                m.put("review_type_name", a.workflow);
                if (!event.equals("processed") && !event.equals("opened")) {
                    m.put("actor", Map.of("account_token", "acc_mock_analyst", "full_name", "Mock Analyst"));
                }
                out.add(m);
            }
        }
        return out;
    }

    public synchronized List<Map<String, Object>> debugView() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (MockAlert a : byExternalId.values()) {
            Map<String, Object> m = importView(a);
            m.put("plan", a.plan.name());
            m.put("alert_token", a.alertToken);
            m.put("outcome", a.outcome);
            m.put("filing_status", a.filingStatus);
            m.put("next_steps", a.steps.stream().map(s -> s.kind() + "@" + s.due()).toList());
            out.add(m);
        }
        return out;
    }

    // -------------------------------------------------------------- control

    /** Runs the next scheduled step of an alert right now. */
    public synchronized boolean advance(String externalId) {
        MockAlert a = byExternalId.get(externalId);
        if (a == null) throw new ApiException(HttpStatus.NOT_FOUND, "not_found", "Unknown alert id " + externalId);
        Step s = a.steps.pollFirst();
        if (s == null) return false;
        run(a, s.kind());
        return true;
    }

    public synchronized int fastForward(String externalId) {
        int n = 0;
        while (advance(externalId)) n++;
        return n;
    }

    public synchronized void merge(String targetCase, List<String> sourceCases) {
        for (MockAlert a : byExternalId.values()) {
            if (a.caseToken != null && sourceCases.contains(a.caseToken)) {
                a.caseToken = targetCase;
                event(a, "case.merged", Map.of("into", targetCase));
            }
        }
        List<Map<String, Object>> sources = sourceCases.stream().map(t -> Map.<String, Object>of("token", t)).toList();
        webhooks.send("case.merged", Map.of("case", Map.of("token", targetCase, "source_cases", sources)));
    }

    public synchronized void reset() {
        byExternalId.clear();
        byProcessingToken.clear();
        byAlertToken.clear();
        webhooks.clear();
    }

    // --------------------------------------------------------------- engine

    @Scheduled(fixedDelay = 1000)
    public synchronized void tick() {
        Instant now = Instant.now();
        for (MockAlert a : byExternalId.values()) {
            while (!a.steps.isEmpty() && !a.steps.peekFirst().due().isAfter(now)) {
                run(a, a.steps.pollFirst().kind());
            }
        }
    }

    private void schedule(MockAlert a) {
        Instant t = a.receivedAt.plus(a.directives.delay() != null ? a.directives.delay() : props.processDelay());
        switch (a.plan) {
            case STUCK -> a.steps.add(new Step(t, "stuck"));
            case ERROR -> a.steps.add(new Step(t, "error"));
            case DISMISSED -> a.steps.add(new Step(t, "dismissed"));
            case SUPPRESSED -> a.steps.add(new Step(t, "suppressed"));
            default -> {
                a.steps.add(new Step(t, "process"));
                t = t.plus(props.reviewDelay());
                a.steps.add(new Step(t, "review"));
                if (a.plan != Plan.ESCALATED) {
                    t = t.plus(props.decisionDelay());
                    a.steps.add(new Step(t, "decision"));
                }
                if (a.plan == Plan.FILED || a.plan == Plan.FILING_REJECTED) {
                    t = t.plus(props.filingDelay());
                    a.steps.add(new Step(t, "filing_submitted"));
                    t = t.plus(props.filingDelay());
                    a.steps.add(new Step(t, a.plan == Plan.FILED ? "filing_accepted" : "filing_rejected"));
                }
            }
        }
    }

    private void run(MockAlert a, String kind) {
        Map<String, Object> caseRef = a.caseToken == null ? null : Map.of("token", a.caseToken);
        switch (kind) {
            case "process" -> {
                a.state = "processed";
                a.processedAt = Instant.now();
                a.alertToken = token("alt");
                a.caseToken = token("cas");
                a.reviewToken = token("rev");
                a.outcome = "opened";
                byAlertToken.put(a.alertToken, a);
                caseRef = Map.of("token", a.caseToken);
                event(a, "alert.processed", Map.of("case_token", a.caseToken));
                webhooks.send("alert.processed", Map.of("alert", Map.of(
                        "id", a.externalId, "token", a.alertToken, "case", caseRef)));
                webhooks.send("case.created", Map.of("case", caseRef));
                webhooks.send("review.created", Map.of("review", Map.of("token", a.reviewToken), "case", caseRef));
            }
            case "review" -> {
                if (a.plan != Plan.NOT_FILED) a.outcome = "escalated";
                event(a, "review.completed", Map.of("review_token", a.reviewToken));
                webhooks.send("review.completed", Map.of(
                        "review", Map.of("token", a.reviewToken), "case", caseRef, "account_type", "analyst"));
            }
            case "decision" -> {
                boolean file = a.plan == Plan.FILED || a.plan == Plan.FILING_REJECTED;
                if (!file) a.outcome = "not_filed";
                String choice = file ? "file_sar" : "no_filing";
                event(a, "action.decision_made", Map.of("choice", choice));
                webhooks.send("action.decision_made", Map.of(
                        "action", Map.of("slug", "case_decision", "case", caseRef),
                        "choice", Map.of("slug", choice)));
            }
            case "filing_submitted", "filing_accepted", "filing_rejected" -> {
                String status = switch (kind) {
                    case "filing_submitted" -> "submitted";
                    case "filing_rejected" -> "rejected";
                    default -> "accepted";
                };
                a.filingStatus = status;
                a.outcome = "filed";
                event(a, "filing.status_updated", Map.of("status", status));
                webhooks.send("filing.status_updated", Map.of("case", caseRef, "filing", Map.of("status", status)));
            }
            case "stuck" -> {
                a.state = "stuck";
                a.alertToken = token("alt");
                byAlertToken.put(a.alertToken, a);
                webhooks.send("alert.stuck", Map.of("alert", Map.of("id", a.externalId, "token", a.alertToken)));
            }
            case "error" -> {
                a.state = "error";
                a.error = a.directives.errorMessage() != null ? a.directives.errorMessage()
                        : "Alert could not be processed: referenced entity data is invalid";
            }
            case "dismissed" -> a.state = "dismissed";
            case "suppressed" -> a.state = "suppressed";
            default -> throw new IllegalStateException("unknown step " + kind);
        }
    }

    private void event(MockAlert a, String type, Map<String, Object> detail) {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("type", type);
        e.put("occurred_at", Instant.now().toString());
        e.putAll(detail);
        a.lifecycleEvents.add(e);
    }

    private void unindex(MockAlert a) {
        byExternalId.remove(a.externalId);
        byProcessingToken.remove(a.processingToken);
        if (a.alertToken != null) byAlertToken.remove(a.alertToken);
    }

    // ------------------------------------------------------------ validation

    private static String requireString(Map<String, Object> m, String key, String param) {
        Object v = m.get(key);
        if (!(v instanceof String s) || s.isBlank()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "invalid_request", param + " is required", param);
        }
        return s;
    }

    /** Every alert reference must point at an entity that is present in "data". */
    private static void checkReferences(Map<String, Object> alert, Map<String, Object> data, String at) {
        if (!(alert.get("references") instanceof List<?> refs)) return;
        for (int i = 0; i < refs.size(); i++) {
            if (!(refs.get(i) instanceof Map<?, ?> r)) continue;
            String type = String.valueOf(r.get("type"));
            String id = String.valueOf(r.get("id"));
            String bucket = switch (type) {
                case "individual" -> "individuals";
                case "transaction" -> "transactions";
                case "business" -> "businesses";
                case "account" -> "accounts";
                default -> null;
            };
            if (bucket == null) continue;
            boolean found = data.get(bucket) instanceof List<?> items
                    && items.stream().anyMatch(it -> it instanceof Map<?, ?> m && id.equals(String.valueOf(m.get("id"))));
            if (!found) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "invalid_reference",
                        type + " reference '" + id + "' not found in data." + bucket, at + "references[" + i + "]");
            }
        }
    }

    // ------------------------------------------------------- cases / reviews

    /** Alerts grouped by case token, newest case first (merged cases hold several alerts). */
    private List<Map.Entry<String, List<MockAlert>>> casesNewestFirst() {
        Map<String, List<MockAlert>> byCase = new LinkedHashMap<>();
        for (MockAlert a : byExternalId.values()) {
            if (a.caseToken != null) byCase.computeIfAbsent(a.caseToken, k -> new ArrayList<>()).add(a);
        }
        List<Map.Entry<String, List<MockAlert>>> list = new ArrayList<>(byCase.entrySet());
        list.sort((x, y) -> createdAt(y.getValue()).compareTo(createdAt(x.getValue())));
        return list;
    }

    private static Instant createdAt(List<MockAlert> alerts) {
        return alerts.stream().map(a -> a.processedAt).min(Instant::compareTo).orElse(Instant.EPOCH);
    }

    private static Instant updatedAt(List<MockAlert> alerts) {
        Instant latest = createdAt(alerts);
        for (MockAlert a : alerts) {
            for (Map<String, Object> e : a.lifecycleEvents) {
                Instant at = Instant.parse(String.valueOf(e.get("occurred_at")));
                if (at.isAfter(latest)) latest = at;
            }
        }
        return latest;
    }

    /**
     * GET /cases: {success, cases:[{cursor, case:{token, created_at, updated_at}}], page_info}.
     * Forward paging uses first/after, backward paging last/before (cursors are case tokens). Newest first,
     * or by update time when {@code updatedBetween} is given.
     */
    public synchronized Map<String, Object> listCases(String workflow, Integer first, String after, Integer last,
                                                      String before, String createdBetween, String updatedBetween) {
        DateFilter created = DateFilter.parse(createdBetween, "createdBetween", false);
        DateFilter updated = DateFilter.parse(updatedBetween, "updatedBetween", false);
        List<Map.Entry<String, List<MockAlert>>> all = new ArrayList<>(casesNewestFirst().stream()
                .filter(e -> workflow == null || workflow.isBlank() || e.getValue().stream().anyMatch(a -> workflow.equals(a.workflow)))
                .filter(e -> created == null || created.matches(createdAt(e.getValue())))
                .filter(e -> updated == null || updated.matches(updatedAt(e.getValue())))
                .toList());
        if (updated != null) all.sort((x, y) -> updatedAt(y.getValue()).compareTo(updatedAt(x.getValue())));

        int from = 0;
        int to = all.size();
        if (after != null && !after.isBlank()) from = cursorIndex(all, after, "after") + 1;
        if (before != null && !before.isBlank()) to = cursorIndex(all, before, "before");
        to = Math.max(from, to);
        if (first == null && last == null) first = 25;
        int start = from;
        int end = to;
        if (first != null) end = Math.min(end, start + clamp(first));
        if (last != null) start = Math.max(start, end - clamp(last));

        List<Map<String, Object>> page = new ArrayList<>();
        for (Map.Entry<String, List<MockAlert>> e : all.subList(start, end)) {
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("token", e.getKey());
            c.put("created_at", createdAt(e.getValue()).toString());
            c.put("updated_at", updatedAt(e.getValue()).toString());
            page.add(Map.of("cursor", e.getKey(), "case", c));
        }
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("end_cursor", page.isEmpty() ? null : page.get(page.size() - 1).get("cursor"));
        info.put("has_next_page", end < all.size());
        info.put("has_previous_page", start > 0);
        info.put("start_cursor", page.isEmpty() ? null : page.get(0).get("cursor"));
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("success", true);
        res.put("cases", page);
        res.put("page_info", info);
        return res;
    }

    private static int cursorIndex(List<Map.Entry<String, List<MockAlert>>> all, String cursor, String param) {
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).getKey().equals(cursor)) return i;
        }
        throw new ApiException(HttpStatus.BAD_REQUEST, "invalid_request",
                param + ": cursor '" + cursor + "' is not in this result set", param);
    }

    private static int clamp(int n) {
        return Math.max(0, Math.min(n, 100)); // "may be reduced to arbitrary maximum page size"
    }

    /** GET /cases/{token}: {success, case:{token, id, name, created_at, updated_at, reviews[], filings[]}}. */
    @SuppressWarnings("unchecked")
    public synchronized Map<String, Object> getCase(String caseToken) {
        List<MockAlert> alerts = byExternalId.values().stream().filter(a -> caseToken.equals(a.caseToken)).toList();
        if (alerts.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND, "not_found", "Case " + caseToken + " not found");

        List<Map<String, Object>> reviews = new ArrayList<>();
        List<Map<String, Object>> filings = new ArrayList<>();
        for (MockAlert a : alerts) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("token", a.reviewToken);
            r.put("id", null);
            r.put("internal_control_number", internalControlNumber(a));
            r.put("workflow", a.workflow);
            r.put("stage", stageTitle(a));
            r.put("created_at", a.processedAt.toString());
            r.put("completed_at", isComplete(a) ? updatedAt(List.of(a)).toString() : null);
            r.put("review_due_at", null);
            r.put("first_activity_at", null);
            r.put("status", reviewStatus(a).toLowerCase());
            r.put("tags", a.alertData.get("review_tags") instanceof List<?> t ? t : List.of());
            Map<String, Object> alert = new LinkedHashMap<>();
            alert.put("id", a.externalId);
            alert.put("token", a.alertToken);
            alert.put("rule", a.alertData.get("rule"));
            alert.put("triggered_at", a.alertData.get("triggered_at"));
            alert.put("other_info", a.alertData.get("other_info") instanceof List<?> o ? o : List.of());
            r.put("alerts", List.of(alert));
            r.put("decisions", decisions(a));
            reviews.add(r);
            if (a.filingStatus != null) {
                Map<String, Object> f = new LinkedHashMap<>();
                f.put("jurisdiction", "FINCEN");
                f.put("workflow", "filing");
                f.put("token", "fil_" + a.reviewToken.substring(4));
                f.put("id", a.externalId + "-FIL");
                f.put("filing_institution_id", null);
                filings.add(f);
            }
        }
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("token", caseToken);
        c.put("id", null);
        c.put("name", "Case for " + alerts.get(0).externalId + (alerts.size() > 1 ? " (+" + (alerts.size() - 1) + ")" : ""));
        c.put("created_at", createdAt(alerts).toString());
        c.put("updated_at", updatedAt(alerts).toString());
        c.put("reviews", reviews);
        c.put("filings", filings);
        return Map.of("success", true, "case", c);
    }

    /** Filters of GET /reviews (all optional; raw query-parameter text, null = not sent). */
    public record ReviewQuery(String caseToken, String assigneeEmail, String queueCanonicalId, String typeCanonicalId,
                              String internalControlNumber, String createdAt, String updatedAt, String completedAt,
                              String cancelledAt) {}

    /** GET /reviews: {reviews:[...], next_page_token}; page_size 1..25 (default 25); page_token is an offset. */
    public synchronized Map<String, Object> listReviews(ReviewQuery q, Integer pageSize, String pageToken) {
        int size = pageSize == null ? 25 : pageSize;
        if (size < 1 || size > 25) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "invalid_request", "page_size must be between 1 and 25", "page_size");
        }
        DateFilter created = DateFilter.parse(q.createdAt(), "created_at", false);
        DateFilter updated = DateFilter.parse(q.updatedAt(), "updated_at", false);
        DateFilter completed = DateFilter.parse(q.completedAt(), "completed_at", true);
        DateFilter cancelled = DateFilter.parse(q.cancelledAt(), "cancelled_at", true);

        List<MockAlert> all = casesNewestFirst().stream()
                .filter(e -> blank(q.caseToken()) || e.getKey().equals(q.caseToken()))
                .flatMap(e -> e.getValue().stream())
                .filter(a -> blank(q.assigneeEmail()) || MOCK_ASSIGNEE_EMAIL.equalsIgnoreCase(q.assigneeEmail().trim()))
                .filter(a -> blank(q.queueCanonicalId()) || a.workflow.equals(q.queueCanonicalId()))
                .filter(a -> blank(q.typeCanonicalId()) || a.workflow.equals(q.typeCanonicalId()))
                .filter(a -> blank(q.internalControlNumber()) || internalControlNumber(a).equals(q.internalControlNumber()))
                .filter(a -> created == null || created.matches(a.processedAt))
                .filter(a -> updated == null || updated.matches(updatedAt(List.of(a))))
                .filter(a -> completed == null || completed.matches(isComplete(a) ? updatedAt(List.of(a)) : null))
                .filter(a -> cancelled == null || cancelled.matches(null)) // the mock never cancels a review
                .toList();

        int start = 0;
        try {
            if (pageToken != null && !pageToken.isBlank()) start = Math.max(0, Integer.parseInt(pageToken));
        } catch (NumberFormatException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "invalid_request", "page_token is not valid", "page_token");
        }
        int end = Math.min(all.size(), start + size);
        List<Map<String, Object>> reviews = new ArrayList<>();
        for (MockAlert a : all.subList(Math.min(start, end), end)) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("token", a.reviewToken);
            r.put("case_token", a.caseToken);
            r.put("status", reviewStatus(a));
            r.put("stage", Map.of("title", stageTitle(a), "token", "stg_" + a.reviewToken.substring(4)));
            r.put("queue", Map.of("canonical_id", a.workflow, "name", a.workflow + " queue", "token", "que_" + a.workflow));
            r.put("assignee", Map.of("token", "usr_mock", "email", MOCK_ASSIGNEE_EMAIL, "name", "Mock Analyst"));
            r.put("type_canonical_id", a.workflow);
            r.put("reference_id", null);
            r.put("internal_control_number", internalControlNumber(a));
            r.put("created_at", a.processedAt.toString());
            r.put("updated_at", updatedAt(List.of(a)).toString());
            r.put("completed_at", isComplete(a) ? updatedAt(List.of(a)).toString() : null);
            r.put("review_due_at", null);
            reviews.add(r);
        }
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("reviews", reviews);
        res.put("next_page_token", end < all.size() ? String.valueOf(end) : null);
        return res;
    }

    private static final String MOCK_ASSIGNEE_EMAIL = "analyst@mock.local";

    private static String internalControlNumber(MockAlert a) {
        return "ICN-" + a.externalId;
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private static boolean isComplete(MockAlert a) {
        return "not_filed".equals(a.outcome) || "filed".equals(a.outcome);
    }

    /** Review status enum as documented for GET /reviews. */
    private static String reviewStatus(MockAlert a) {
        return switch (String.valueOf(a.outcome)) {
            case "escalated" -> "READY_FOR_REVIEW";
            case "not_filed", "filed" -> "COMPLETED";
            default -> "OPENED";
        };
    }

    private static String stageTitle(MockAlert a) {
        return switch (String.valueOf(a.outcome)) {
            case "escalated" -> "Escalation";
            case "filed" -> "Filing";
            case "not_filed" -> "Complete";
            default -> "Triage";
        };
    }

    private static List<Map<String, Object>> decisions(MockAlert a) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> e : a.lifecycleEvents) {
            if (!"action.decision_made".equals(e.get("type"))) continue;
            boolean file = "file_sar".equals(e.get("choice"));
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("action_name", "Case decision");
            d.put("action_id", "case_decision");
            d.put("choice_name", file ? "File SAR" : "No filing");
            d.put("choice_id", e.get("choice"));
            d.put("decision_made_at", e.get("occurred_at"));
            d.put("arbiter", Map.of("token", "usr_mock", "email", "analyst@mock.local", "name", "Mock Analyst"));
            out.add(d);
        }
        return out;
    }
}
