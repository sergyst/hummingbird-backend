package com.example.alerts.service;

import com.example.alerts.client.HummingbirdApiException;
import com.example.alerts.client.HummingbirdClient;
import com.example.alerts.domain.AlertEvent;
import com.example.alerts.domain.AlertEventRepository;
import com.example.alerts.domain.AlertRecord;
import com.example.alerts.domain.AlertRepository;
import com.example.alerts.domain.AlertState;
import com.example.alerts.web.Dtos.DayCount;
import com.example.alerts.web.Dtos.RefreshSummary;
import com.example.alerts.web.Dtos.Stats;
import com.example.alerts.web.Dtos.SubmitAlertRequest;
import com.example.alerts.web.Dtos.TransactionInput;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Orchestrates the Hummingbird alert lifecycle:
 * submit -> (async processing) -> processed | stuck | error -> case outcome (opened / escalated / filed / not_filed ...),
 * tracked by polling and by webhooks.
 *
 * <p>Note: no DB transaction is held while calling Hummingbird over HTTP.
 */
@Service
public class AlertService {

    private static final Logger log = LoggerFactory.getLogger(AlertService.class);

    /** Outcomes that will not change any more. */
    private static final Set<String> FINAL_OUTCOMES = Set.of("filed", "not_filed", "dismissed");
    /** How often a processed alert with a non-final outcome is re-checked when no webhook told us anything. */
    private static final Duration OUTCOME_RECHECK = Duration.ofMinutes(2);

    private final AlertRepository alerts;
    private final AlertEventRepository events;
    private final HummingbirdClient client;

    public AlertService(AlertRepository alerts, AlertEventRepository events, HummingbirdClient client) {
        this.alerts = alerts;
        this.events = events;
        this.client = client;
    }

    // ------------------------------------------------------------------ read

    public Page<AlertRecord> search(AlertState state, String q, String outcome, int page, int size) {
        String text = q == null ? "" : q.trim();
        String out = outcome == null ? "" : outcome.trim().toLowerCase(Locale.ROOT);
        return alerts.search(state == null, state == null ? AlertState.PENDING : state, text, out,
                PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100),
                        Sort.by(Sort.Direction.DESC, "createdAt")));
    }

    public AlertRecord get(Long id) {
        return alerts.findById(id).orElseThrow(() -> new NoSuchElementException("Alert " + id + " not found"));
    }

    public List<AlertEvent> eventsFor(Long id) {
        return events.findByAlertIdOrderByAtDesc(id);
    }

    public Stats stats() {
        Map<String, Long> byState = new LinkedHashMap<>();
        for (AlertState s : AlertState.values()) {
            byState.put(s.name(), 0L);
        }
        long total = 0;
        for (Object[] row : alerts.countByState()) {
            long n = ((Number) row[1]).longValue();
            byState.put(((AlertState) row[0]).name(), n);
            total += n;
        }
        Map<String, Long> byOutcome = new LinkedHashMap<>();
        for (Object[] row : alerts.countByOutcome()) {
            byOutcome.put(String.valueOf(row[0]), ((Number) row[1]).longValue());
        }

        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        Instant since = today.minusDays(13).atStartOfDay().toInstant(ZoneOffset.UTC);
        List<AlertRecord> recent = alerts.findByCreatedAtAfter(since);
        Map<LocalDate, Long> perDay = new LinkedHashMap<>();
        for (int i = 13; i >= 0; i--) {
            perDay.put(today.minusDays(i), 0L);
        }
        for (AlertRecord a : recent) {
            perDay.merge(a.getCreatedAt().atZone(ZoneOffset.UTC).toLocalDate(), 1L, Long::sum);
        }
        double sumSeconds = 0;
        int processedCount = 0;
        for (AlertRecord a : alerts.findAll()) {
            if (a.getProcessedAt() != null) {
                sumSeconds += Duration.between(a.getCreatedAt(), a.getProcessedAt()).toMillis() / 1000.0;
                processedCount++;
            }
        }
        List<DayCount> days = new ArrayList<>();
        perDay.forEach((d, n) -> days.add(new DayCount(d, n)));
        return new Stats(total, byState, byOutcome, days, processedCount == 0 ? null : sumSeconds / processedCount);
    }

    // ---------------------------------------------------------------- submit

    /** Sends the alert to Hummingbird (POST /alert_imports) and stores it locally as PENDING. */
    public AlertRecord submit(SubmitAlertRequest req) {
        String externalId = req.id() == null || req.id().isBlank()
                ? "ALR-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT)
                : req.id().trim();
        if (alerts.existsByExternalId(externalId)) {
            throw new DuplicateAlertException(externalId);
        }

        Instant triggeredAt = req.triggeredAt() == null ? Instant.now() : req.triggeredAt();
        List<TransactionInput> txs = effectiveTransactions(req);
        Map<String, Object> response = client.upsertAlertImport(buildPayload(req, txs, externalId, triggeredAt));

        String processingToken = extractProcessingToken(response, externalId);

        AlertRecord rec = new AlertRecord();
        rec.setExternalId(externalId);
        rec.setProcessingToken(processingToken);
        rec.setRule(req.rule().trim());
        rec.setComments(blankToNull(req.comments()));
        rec.setTriggeredAt(triggeredAt);
        rec.setReviewTags(req.reviewTags() == null ? null : String.join(",", req.reviewTags().stream()
                .map(String::trim).filter(s -> !s.isEmpty()).toList()));
        rec.setSubjectName(blankToNull(req.subjectName()));
        if (!txs.isEmpty()) {
            rec.setTxCount(txs.size());
            rec.setAmountMinor(txs.stream().mapToLong(t -> t.amountMinor() == null ? 0 : t.amountMinor()).sum());
            rec.setCurrency(currencyOf(txs.get(0)));
        }
        rec.setState(AlertState.PENDING);
        rec.setSource(AlertRecord.Source.CONSOLE);
        try {
            rec = alerts.save(rec);
        } catch (DataIntegrityViolationException e) {
            throw new DuplicateAlertException(externalId);
        }
        audit(rec, "SUBMITTED", "Accepted by Hummingbird; processing token " + processingToken
                + (txs.isEmpty() ? "" : "; " + txs.size() + " transaction(s)"));
        return rec;
    }

    /** The transactions to send: the explicit list, else the single-amount shortcut, else none. */
    private static List<TransactionInput> effectiveTransactions(SubmitAlertRequest req) {
        if (req.transactions() != null && !req.transactions().isEmpty()) {
            return req.transactions();
        }
        if (req.amountMinor() != null) {
            return List.of(new TransactionInput(req.amountMinor(), req.currency(), req.direction(), null, null));
        }
        return List.of();
    }

    private static String currencyOf(TransactionInput t) {
        return t.currency() == null || t.currency().isBlank() ? "USD" : t.currency().trim().toUpperCase(Locale.ROOT);
    }

    private Map<String, Object> buildPayload(SubmitAlertRequest req, List<TransactionInput> txs,
                                             String externalId, Instant triggeredAt) {
        Map<String, Object> alert = new LinkedHashMap<>();
        alert.put("id", externalId);
        alert.put("triggered_at", triggeredAt.toString());
        alert.put("rule", req.rule().trim());
        if (req.comments() != null && !req.comments().isBlank()) {
            alert.put("comments", req.comments().trim());
        }
        if (req.reviewTags() != null && !req.reviewTags().isEmpty()) {
            alert.put("review_tags", req.reviewTags().stream().map(String::trim).filter(s -> !s.isEmpty()).toList());
        }

        List<Map<String, Object>> references = new ArrayList<>();
        Map<String, Object> data = new LinkedHashMap<>();

        if (req.subjectName() != null && !req.subjectName().isBlank()) {
            String individualId = "IND-" + externalId;
            data.put("individuals", List.of(Map.of(
                    "id", individualId,
                    "names", List.of(Map.of("name", req.subjectName().trim())))));
            references.add(Map.of("type", "individual", "id", individualId));
        }

        if (!txs.isEmpty()) {
            List<Map<String, Object>> payloadTxs = new ArrayList<>();
            for (int i = 0; i < txs.size(); i++) {
                TransactionInput t = txs.get(i);
                String txId = "TX-" + externalId + "-" + (i + 1);
                // amount and amount_local are both required by the schema
                Map<String, Object> money = Map.of("amount", t.amountMinor() == null ? 0L : t.amountMinor(),
                        "currency_code", currencyOf(t));
                Map<String, Object> tx = new LinkedHashMap<>();
                tx.put("id", txId);
                tx.put("amount", money);
                tx.put("amount_local", money);
                tx.put("initiated_at", (t.initiatedAt() == null ? triggeredAt : t.initiatedAt()).toString());
                tx.put("direction", "CREDIT".equalsIgnoreCase(t.direction()) ? "CREDIT" : "DEBIT");
                if (t.description() != null && !t.description().isBlank()) {
                    tx.put("description", t.description().trim());
                }
                payloadTxs.add(tx);
                references.add(Map.of("type", "transaction", "id", txId));
            }
            data.put("transactions", payloadTxs);
        }

        if (!references.isEmpty()) {
            alert.put("references", references);
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("alerts", List.of(alert));
        if (!data.isEmpty()) {
            payload.put("data", data);
        }
        return payload;
    }

    private static String extractProcessingToken(Map<String, Object> response, String externalId) {
        if (response != null && response.get("alerts") instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof Map<?, ?> m && externalId.equals(String.valueOf(m.get("id"))) && m.get("token") != null) {
                    return String.valueOf(m.get("token"));
                }
            }
        }
        throw new HummingbirdApiException(502, "BAD_RESPONSE",
                "Hummingbird accepted the request but returned no processing token for alert " + externalId, null);
    }

    // --------------------------------------------------------------- refresh

    /** Polls GET /alert_imports/{token} for one alert and stores any change. */
    public AlertRecord refresh(Long id) {
        AlertRecord rec = get(id);
        if (rec.getProcessingToken() == null) {
            throw new IllegalStateException(
                    "This alert has no processing token (it was learned from a webhook), so it cannot be polled.");
        }
        Map<String, Object> res = client.getAlertImport(rec.getProcessingToken());
        AlertState before = rec.getState();
        boolean changed = applyImportResponse(rec, res);
        if (before != AlertState.PROCESSED && rec.getState() == AlertState.PROCESSED) {
            rec.setOutcomeStale(true); // the case now exists: go and ask for its outcome
        }
        rec.setLastCheckedAt(Instant.now());
        rec = alerts.save(rec);
        if (changed) {
            audit(rec, "POLLED", "State is now " + rec.getState());
        }
        return rec;
    }

    public RefreshSummary refreshAllPending() {
        int checked = 0, changed = 0, failed = 0;
        for (AlertRecord rec : alerts.findByStateOrderByCreatedAtAsc(AlertState.PENDING)) {
            if (rec.getProcessingToken() == null) {
                continue;
            }
            checked++;
            AlertState before = rec.getState();
            try {
                AlertRecord after = refresh(rec.getId());
                if (after.getState() != before) {
                    changed++;
                }
            } catch (HummingbirdApiException e) {
                failed++;
                log.warn("Polling alert {} failed: {} ({})", rec.getExternalId(), e.getMessage(), e.getCode());
                if (e.getStatus() == 503 || e.getStatus() == 401) {
                    break; // not configured / bad credentials: no point continuing
                }
            }
        }
        return new RefreshSummary(checked, changed, failed);
    }

    // --------------------------------------------------------------- outcome

    /**
     * Case-alert lifecycle (opened / escalated / filed / not_filed ...) via POST /alerts/id_lookup.
     * Stores the outcome and returns Hummingbird's raw response for display.
     */
    public Map<String, Object> lifecycle(Long id) {
        AlertRecord rec = get(id);
        Map<String, Object> res = client.lookupCaseAlert(rec.getExternalId());
        applyCaseAlert(rec, res);
        return res;
    }

    /**
     * Re-checks the outcome of processed alerts whose outcome may have moved on: ones flagged by a
     * webhook, and (as a fallback when webhooks are not set up) non-final ones not checked for a while.
     */
    public RefreshSummary syncOutcomesDue() {
        int checked = 0, changed = 0, failed = 0;
        Instant now = Instant.now();
        for (AlertRecord rec : alerts.findByStateOrderByCreatedAtAsc(AlertState.PROCESSED)) {
            boolean isFinal = rec.getOutcome() != null && FINAL_OUTCOMES.contains(rec.getOutcome());
            boolean due = rec.isOutcomeStale()
                    || (!isFinal && (rec.getOutcomeCheckedAt() == null
                    || Duration.between(rec.getOutcomeCheckedAt(), now).compareTo(OUTCOME_RECHECK) > 0));
            if (!due) {
                continue;
            }
            checked++;
            String before = rec.getOutcome();
            try {
                AlertRecord after = applyCaseAlert(rec, client.lookupCaseAlert(rec.getExternalId()));
                if (after.getOutcome() != null && !after.getOutcome().equals(before)) {
                    changed++;
                }
            } catch (HummingbirdApiException e) {
                failed++;
                // The case alert may not be queryable yet right after processing: try again next round.
                log.debug("Outcome lookup for {} failed: {} ({})", rec.getExternalId(), e.getMessage(), e.getCode());
                if (e.getStatus() == 503 || e.getStatus() == 401) {
                    break;
                }
            }
        }
        return new RefreshSummary(checked, changed, failed);
    }

    @SuppressWarnings("unchecked")
    private AlertRecord applyCaseAlert(AlertRecord rec, Map<String, Object> res) {
        if (res == null || !(res.get("case_alert") instanceof Map<?, ?> raw)) {
            return rec;
        }
        Map<String, Object> ca = (Map<String, Object>) raw;
        String before = rec.getOutcome();
        String outcome = str(ca.get("outcome"));
        if (outcome != null) {
            rec.setOutcome(outcome.toLowerCase(Locale.ROOT));
        }
        if (ca.get("token") != null) {
            rec.setAlertToken(String.valueOf(ca.get("token")));
        }
        rec.setOutcomeCheckedAt(Instant.now());
        rec.setOutcomeStale(false);
        rec = alerts.save(rec);
        if (rec.getOutcome() != null && !rec.getOutcome().equals(before)) {
            audit(rec, "OUTCOME", (before == null ? "" : before + " → ") + rec.getOutcome());
        }
        return rec;
    }

    @SuppressWarnings("unchecked")
    private boolean applyImportResponse(AlertRecord rec, Map<String, Object> res) {
        if (res == null || !(res.get("alert") instanceof Map<?, ?> raw)) {
            return false;
        }
        Map<String, Object> a = (Map<String, Object>) raw;
        boolean changed = false;

        AlertState state = AlertState.fromApi(a.get("state"));
        if (state != rec.getState()) {
            rec.setState(state);
            changed = true;
        }
        changed |= setIfDifferent(a.get("case_token"), rec.getCaseToken(), rec::setCaseToken);
        changed |= setIfDifferent(a.get("review_token"), rec.getReviewToken(), rec::setReviewToken);
        changed |= setIfDifferent(a.get("error"), rec.getErrorMessage(), rec::setErrorMessage);
        if (a.get("processed_at") != null && rec.getProcessedAt() == null) {
            try {
                rec.setProcessedAt(Instant.parse(String.valueOf(a.get("processed_at"))));
            } catch (RuntimeException e) {
                rec.setProcessedAt(Instant.now());
            }
            changed = true;
        }
        return changed;
    }

    private static boolean setIfDifferent(Object incoming, String current, Consumer<String> setter) {
        String value = incoming == null ? null : String.valueOf(incoming);
        if (value == null || value.equals(current)) {
            return false;
        }
        setter.accept(value);
        return true;
    }

    // --------------------------------------------------------------- webhook

    /**
     * Applies a webhook event. Handled (payloads per the Hummingbird Webhook Event Library):
     * alert.processed, alert.stuck, case.created, case.merged, review.created, review.completed,
     * action.decision_made, filing.status_updated. Other event types are ignored.
     */
    public void applyWebhook(String type, Map<String, Object> data) {
        switch (type) {
            case "alert.processed", "alert.stuck" -> applyAlertEvent(type, data);
            case "case.created", "case.merged", "review.created", "review.completed",
                 "action.decision_made", "filing.status_updated" -> applyCaseEvent(type, data);
            default -> log.debug("Ignoring webhook event type {}", type);
        }
    }

    private void applyAlertEvent(String type, Map<String, Object> data) {
        Object idValue = path(data, "alert", "id");
        if (idValue == null) {
            return;
        }
        String externalId = String.valueOf(idValue);

        AlertRecord rec = alerts.findByExternalId(externalId).orElseGet(() -> {
            AlertRecord r = new AlertRecord();
            r.setExternalId(externalId);
            r.setSource(AlertRecord.Source.WEBHOOK);
            r.setRule("(received via webhook)");
            return r;
        });

        String alertToken = str(path(data, "alert", "token"));
        if (alertToken != null) {
            rec.setAlertToken(alertToken);
        }
        if ("alert.processed".equals(type)) {
            rec.setState(AlertState.PROCESSED);
            if (rec.getProcessedAt() == null) {
                rec.setProcessedAt(Instant.now());
            }
            String caseToken = str(path(data, "alert", "case", "token"));
            if (caseToken != null) {
                rec.setCaseToken(caseToken);
            }
            rec.setOutcomeStale(true);
        } else {
            rec.setState(AlertState.STUCK);
        }
        rec = alerts.save(rec);
        audit(rec, "WEBHOOK", type);
    }

    /** Case-level events: find our alerts by case token and record what happened. */
    private void applyCaseEvent(String type, Map<String, Object> data) {
        if ("case.merged".equals(type)) {
            applyCaseMerged(data);
            return;
        }
        String caseToken = "action.decision_made".equals(type)
                ? str(path(data, "action", "case", "token"))
                : str(path(data, "case", "token"));
        if (caseToken == null) {
            return;
        }
        for (AlertRecord rec : alerts.findByCaseToken(caseToken)) {
            String detail = type;
            switch (type) {
                case "review.created" -> {
                    String review = str(path(data, "review", "token"));
                    if (review != null && rec.getReviewToken() == null) {
                        rec.setReviewToken(review);
                    }
                    detail = "Review created";
                }
                case "review.completed" -> detail = "Review completed"
                        + (path(data, "account_type") == null ? "" : " by " + path(data, "account_type"));
                case "action.decision_made" -> detail = "Decision: " + str(path(data, "choice", "slug"))
                        + (path(data, "action", "slug") == null ? "" : " (" + path(data, "action", "slug") + ")");
                case "filing.status_updated" -> {
                    String status = str(path(data, "filing", "status"));
                    if (status != null) {
                        rec.setFilingStatus(status);
                    }
                    detail = "Filing status: " + status;
                }
                default -> detail = "Case created";
            }
            // anything after "case created" may have changed the outcome
            rec.setOutcomeStale(!"case.created".equals(type));
            rec = alerts.save(rec);
            audit(rec, "WEBHOOK", detail);
        }
    }

    private void applyCaseMerged(Map<String, Object> data) {
        String target = str(path(data, "case", "token"));
        if (target == null || !(path(data, "case", "source_cases") instanceof List<?> sources)) {
            return;
        }
        for (Object s : sources) {
            String sourceToken = s instanceof Map<?, ?> m ? str(m.get("token")) : null;
            if (sourceToken == null) {
                continue;
            }
            for (AlertRecord rec : alerts.findByCaseToken(sourceToken)) {
                rec.setCaseToken(target);
                rec.setOutcomeStale(true);
                rec = alerts.save(rec);
                audit(rec, "WEBHOOK", "Case merged into " + target);
            }
        }
    }

    // ---------------------------------------------------------------- delete

    /** Removes the local copy only; nothing is deleted in Hummingbird. */
    @Transactional
    public void deleteLocal(Long id) {
        AlertRecord rec = get(id);
        events.deleteByAlertId(rec.getId());
        alerts.delete(rec);
    }

    // --------------------------------------------------------------- helpers

    private void audit(AlertRecord rec, String type, String detail) {
        events.save(new AlertEvent(rec.getId(), type, detail));
    }

    /** Null-safe walk through nested JSON maps. */
    private static Object path(Object root, String... keys) {
        Object cur = root;
        for (String k : keys) {
            if (!(cur instanceof Map<?, ?> m)) {
                return null;
            }
            cur = m.get(k);
        }
        return cur;
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    public static class DuplicateAlertException extends RuntimeException {
        public DuplicateAlertException(String externalId) {
            super("An alert with id '" + externalId + "' already exists. Hummingbird treats a repeated alert id as a duplicate.");
        }
    }
}
