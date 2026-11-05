package com.example.alerts.domain;

/** Processing state of an alert in Hummingbird (mirrors the API's {@code state} values). */
public enum AlertState {
    PENDING,
    PROCESSED,
    ERROR,
    STUCK,
    DISMISSED,
    SUPPRESSED;

    public static AlertState fromApi(Object value) {
        if (value == null) {
            return PENDING;
        }
        try {
            return valueOf(String.valueOf(value).trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return PENDING;
        }
    }

    /** Terminal states never change by polling again. */
    public boolean isFinal() {
        return this == PROCESSED || this == DISMISSED || this == SUPPRESSED;
    }
}
