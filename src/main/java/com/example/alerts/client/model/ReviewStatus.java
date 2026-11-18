package com.example.alerts.client.model;

import java.util.Locale;

/** Status of a review as documented for GET /reviews. */
public enum ReviewStatus {
    CREATED, OPENED, WORK_STARTED, READY_FOR_REVIEW, APPROVED, CHANGES_REQUESTED, PENDING, COMPLETED, CANCELLED;

    /** Nothing more will happen to a review in one of these states. */
    public boolean isDone() {
        return this == COMPLETED || this == CANCELLED || this == APPROVED;
    }

    /** Case-insensitive; null for null, blank or an undocumented value (the API may add statuses). */
    public static ReviewStatus fromApi(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
