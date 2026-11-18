package com.example.alerts.mock;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;

/** One alert as Hummingbird would hold it. Guarded by {@link MockWorld}'s lock. */
public class MockAlert {

    /** What the mock will do with the alert; picked from the submitted rule/comments/tags. */
    public enum Plan { FILED, FILING_REJECTED, NOT_FILED, ESCALATED, STUCK, ERROR, DISMISSED, SUPPRESSED }

    public record Step(Instant due, String kind) {}

    final String externalId;
    final String processingToken;
    final Instant receivedAt = Instant.now();
    final Plan plan;
    final Directives directives;
    final String workflow;
    final Map<String, Object> alertData;
    final Map<String, Object> payload;
    final Deque<Step> steps = new ArrayDeque<>();
    final List<Map<String, Object>> lifecycleEvents = new ArrayList<>();

    String state = "pending";
    String error;
    Instant processedAt;
    String alertToken;
    String caseToken;
    String reviewToken;
    String outcome;
    String filingStatus;

    MockAlert(String externalId, String processingToken, Directives directives, Map<String, Object> alertData,
              Map<String, Object> payload) {
        this.externalId = externalId;
        this.processingToken = processingToken;
        this.directives = directives;
        this.plan = directives.plan();
        this.alertData = alertData;
        this.payload = payload;
        Object wf = payload.get("workflow");
        this.workflow = wf instanceof String w && !w.isBlank() ? w : "triage";
    }
}
