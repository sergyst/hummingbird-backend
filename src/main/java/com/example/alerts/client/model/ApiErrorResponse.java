package com.example.alerts.client.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/** Documented error body: {@code {success:false, error:{type, message}}} (+ optional {@code trace_id}). */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ApiErrorResponse(Boolean success, ApiError error, @JsonProperty("trace_id") String traceId) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ApiError(String type, String code, String message, String param) {
    }
}
