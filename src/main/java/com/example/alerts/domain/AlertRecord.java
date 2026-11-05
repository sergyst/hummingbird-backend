package com.example.alerts.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;

/** Local copy of an alert we submitted (or learned about through a webhook). */
@Entity
@Table(name = "alerts",
        uniqueConstraints = @UniqueConstraint(name = "uk_alert_external_id", columnNames = "external_id"),
        indexes = {@Index(name = "ix_alert_state", columnList = "state"),
                @Index(name = "ix_alert_created", columnList = "created_at")})
public class AlertRecord {

    public enum Source { CONSOLE, WEBHOOK }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Our alert id, sent to Hummingbird as {@code alerts[].id}. */
    @Column(name = "external_id", nullable = false, length = 256)
    private String externalId;

    /** Token returned by POST /alert_imports, used to poll GET /alert_imports/{token}. */
    @Column(length = 64)
    private String processingToken;

    /** Token of the processed alert (from the alert.processed webhook). */
    @Column(length = 64)
    private String alertToken;

    @Column(length = 256)
    private String rule;

    @Column(length = 49000)
    private String comments;

    private Instant triggeredAt;

    /** Comma-separated review tags. */
    @Column(length = 2000)
    private String reviewTags;

    @Column(length = 256)
    private String subjectName;

    /** Optional transaction amount in minor units (e.g. cents). */
    private Long amountMinor;

    @Column(length = 8)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private AlertState state = AlertState.PENDING;

    @Column(length = 64)
    private String caseToken;

    @Column(length = 64)
    private String reviewToken;

    @Column(length = 2000)
    private String errorMessage;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Source source = Source.CONSOLE;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    private Instant processedAt;

    private Instant lastCheckedAt;

    /** Case-alert outcome from Hummingbird: processed, opened, escalated, filed, not_filed, dismissed. */
    @Column(length = 32)
    private String outcome;

    /** SAR/STR filing status from filing.status_updated webhooks (e.g. accepted). */
    @Column(length = 32)
    private String filingStatus;

    /** Number of transactions sent with the alert. */
    private Integer txCount;

    /** True when something happened (webhook, new case) that may have changed the outcome. */
    private boolean outcomeStale;

    private Instant outcomeCheckedAt;

    public Long getId() { return id; }
    public String getExternalId() { return externalId; }
    public void setExternalId(String externalId) { this.externalId = externalId; }
    public String getProcessingToken() { return processingToken; }
    public void setProcessingToken(String processingToken) { this.processingToken = processingToken; }
    public String getAlertToken() { return alertToken; }
    public void setAlertToken(String alertToken) { this.alertToken = alertToken; }
    public String getRule() { return rule; }
    public void setRule(String rule) { this.rule = rule; }
    public String getComments() { return comments; }
    public void setComments(String comments) { this.comments = comments; }
    public Instant getTriggeredAt() { return triggeredAt; }
    public void setTriggeredAt(Instant triggeredAt) { this.triggeredAt = triggeredAt; }
    public String getReviewTags() { return reviewTags; }
    public void setReviewTags(String reviewTags) { this.reviewTags = reviewTags; }
    public String getSubjectName() { return subjectName; }
    public void setSubjectName(String subjectName) { this.subjectName = subjectName; }
    public Long getAmountMinor() { return amountMinor; }
    public void setAmountMinor(Long amountMinor) { this.amountMinor = amountMinor; }
    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }
    public AlertState getState() { return state; }
    public void setState(AlertState state) { this.state = state; }
    public String getCaseToken() { return caseToken; }
    public void setCaseToken(String caseToken) { this.caseToken = caseToken; }
    public String getReviewToken() { return reviewToken; }
    public void setReviewToken(String reviewToken) { this.reviewToken = reviewToken; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    public Source getSource() { return source; }
    public void setSource(Source source) { this.source = source; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getProcessedAt() { return processedAt; }
    public void setProcessedAt(Instant processedAt) { this.processedAt = processedAt; }
    public Instant getLastCheckedAt() { return lastCheckedAt; }
    public void setLastCheckedAt(Instant lastCheckedAt) { this.lastCheckedAt = lastCheckedAt; }
    public String getOutcome() { return outcome; }
    public void setOutcome(String outcome) { this.outcome = outcome; }
    public String getFilingStatus() { return filingStatus; }
    public void setFilingStatus(String filingStatus) { this.filingStatus = filingStatus; }
    public Integer getTxCount() { return txCount; }
    public void setTxCount(Integer txCount) { this.txCount = txCount; }
    public boolean isOutcomeStale() { return outcomeStale; }
    public void setOutcomeStale(boolean outcomeStale) { this.outcomeStale = outcomeStale; }
    public Instant getOutcomeCheckedAt() { return outcomeCheckedAt; }
    public void setOutcomeCheckedAt(Instant outcomeCheckedAt) { this.outcomeCheckedAt = outcomeCheckedAt; }
}
