package com.example.alerts.client.model;

import java.time.Instant;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Processing state of an alert import. {@code state} is one of pending, processed, error, stuck, dismissed,
 * suppressed (see {@code AlertState.fromApi}). {@code caseToken} / {@code reviewToken} are set once processed.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AlertImportStatus(
        String token,
        String id,
        String state,
        @JsonProperty("case_token") String caseToken,
        @JsonProperty("review_token") String reviewToken,
        @JsonProperty("processed_at") Instant processedAt,
        String error) {
}
