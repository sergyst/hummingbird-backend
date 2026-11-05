package com.example.alerts.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.Instant;

/** Audit trail entry: what happened to an alert and when (submit, poll, webhook...). */
@Entity
@Table(name = "alert_events", indexes = @Index(name = "ix_event_alert", columnList = "alert_id"))
public class AlertEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "alert_id", nullable = false)
    private Long alertId;

    /** SUBMITTED, POLLED, WEBHOOK, ... */
    @Column(nullable = false, length = 32)
    private String type;

    @Column(length = 1000)
    private String detail;

    @Column(name = "occurred_at", nullable = false)
    private Instant at = Instant.now();

    protected AlertEvent() {
    }

    public AlertEvent(Long alertId, String type, String detail) {
        this.alertId = alertId;
        this.type = type;
        this.detail = detail == null ? null : (detail.length() > 1000 ? detail.substring(0, 1000) : detail);
    }

    public Long getId() { return id; }
    public Long getAlertId() { return alertId; }
    public String getType() { return type; }
    public String getDetail() { return detail; }
    public Instant getAt() { return at; }
}
