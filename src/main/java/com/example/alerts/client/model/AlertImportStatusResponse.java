package com.example.alerts.client.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/** GET /alert_imports/{token}: {@code {success, alert:{...}}}. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AlertImportStatusResponse(Boolean success, AlertImportStatus alert) {
}
