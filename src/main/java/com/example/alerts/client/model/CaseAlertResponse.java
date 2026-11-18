package com.example.alerts.client.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/** POST /alerts/id_lookup (303 to GET /alerts/{token}): {@code {case_alert:{...}}}. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CaseAlertResponse(@JsonProperty("case_alert") CaseAlert caseAlert) {
}
